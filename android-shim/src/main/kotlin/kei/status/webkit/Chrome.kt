package kei.status.webkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thrown when no usable Chromium binary can be found or it fails to start. */
class ChromeUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Launches one headless Chromium per JVM and hands out page sessions over the DevTools protocol.
 *
 * A real browser is the only honest way to run `runWebView`: sources use it to execute a site's own
 * JavaScript (challenge solvers, SPA readers, localStorage probes), which cannot be reproduced by a
 * DOM emulation. Chromium is started lazily, on the first WebView, so sources that never touch one
 * pay nothing.
 */
object Chrome {

    private const val CHROME_ENV = "KEI_STATUS_CHROME"

    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Shared client for the DevTools HTTP endpoints: [newPage]'s target creation and the
     * target close every [Page.close] issues. One client means one pool, instead of a new
     * one per close whose threads never shut down.
     */
    internal val HTTP = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * DevTools targets this JVM created and has not retired.
     *
     * Extensions do not always destroy their WebViews — a source that throws inside
     * `runWebView` before its own cleanup, or one that never destroys at all, leaves the
     * browser holding the tab. The registry lets the end of a module retire every page it
     * leaked, so one Chromium can serve a whole sweep without accumulating tabs.
     */
    private val liveTargets = ConcurrentHashMap<String, Unit>()

    internal fun registerTarget(id: String) { liveTargets[id] = Unit }

    internal fun unregisterTarget(id: String) { liveTargets.remove(id) }

    /**
     * Retires every live page, whatever holds it.
     *
     * Closing over HTTP makes the browser drop each page's WebSocket, so any poller or
     * watcher riding that socket winds down on its next tick. Called at module boundaries;
     * closing a target that already went away only answers with an error, which is swallowed.
     */
    fun closeAllPages() {
        val ids = liveTargets.keys.toList()
        liveTargets.clear()
        val base = if (isRunning()) port else return
        ids.forEach { id ->
            runCatching {
                val request = Request.Builder()
                    .url("http://127.0.0.1:$base/json/close/$id")
                    .build()
                HTTP.newCall(request).execute().close()
            }
        }
    }

    private val candidates = listOf(
        "google-chrome-stable",
        "google-chrome",
        "chromium",
        "chromium-browser",
        "chrome",
        "microsoft-edge",
    )

    @Volatile
    private var process: Process? = null

    @Volatile
    private var port: Int = 0

    @Volatile
    private var profileDir: File? = null

    private val lock = Any()

    fun isRunning(): Boolean = process?.isAlive == true && port > 0

    /**
     * The DevTools HTTP port, starting Chromium if needed.
     *
     * @throws ChromeUnavailableException when no binary exists or it never opens its port.
     */
    fun port(): Int {
        if (isRunning()) return port
        synchronized(lock) {
            if (isRunning()) return port
            stop()
            val binary = findBinary()
            val dir = Files.createTempDirectory("kei-status-chrome").toFile()
            profileDir = dir

            val command = buildList {
                add(binary)
                // A visible browser clears challenges that headless Chromium cannot: headless is
                // distinguishable to the zone, and a challenge it refuses never becomes solvable.
                // So a display is used whenever one exists, and headless is only the fallback for
                // a machine that has none.
                if (System.getenv("DISPLAY") == null) {
                    add("--headless=new")
                } else {
                    // There is usually no window manager on a virtual display, so Chrome treats
                    // its window as occluded and throttles the page.
                    add("--disable-backgrounding-occluded-windows")
                    add("--disable-renderer-backgrounding")
                    add("--disable-background-timer-throttling")
                }
                add("--disable-gpu")
                add("--no-sandbox")
                add("--disable-dev-shm-usage")
                add("--no-first-run")
                add("--no-default-browser-check")
                add("--disable-background-networking")
                add("--disable-sync")
                add("--disable-extensions")
                add("--disable-component-update")
                add("--mute-audio")
                add("--hide-scrollbars")
                // A real Android WebView is not flagged as an automation target; without these
                // switches sites answer headless Chromium differently from the app, which would
                // make the harness report anti-bot blocks the app never sees.
                add("--disable-blink-features=AutomationControlled")
                add("--exclude-switches=enable-automation")
                add("--window-size=1280,1920")
                add("--user-data-dir=${dir.absolutePath}")
                add("--remote-debugging-port=0")
                add("about:blank")
            }

            val started = try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("chrome.log"))
                    .start()
            } catch (e: Exception) {
                throw ChromeUnavailableException("could not start $binary: ${e.message}", e)
            }
            process = started

            val activePort = dir.resolve("DevToolsActivePort")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                if (!started.isAlive) {
                    val log = dir.resolve("chrome.log").takeIf { it.isFile }?.readText().orEmpty()
                    throw ChromeUnavailableException(
                        "$binary exited before opening a DevTools port:\n${log.takeLast(2000)}",
                    )
                }
                val line = runCatching { activePort.readLines().firstOrNull() }.getOrNull()
                val portNumber = line?.trim()?.toIntOrNull()
                if (portNumber != null) {
                    port = portNumber
                    return port
                }
                Thread.sleep(100)
            }
            throw ChromeUnavailableException("$binary did not open a DevTools port within 30s")
        }
    }

    /**
     * Opens a blank page and connects a [Page] session to it.
     *
     * [pageFactory] builds the session; it exists so tests can fail the build the way the real
     * constructor does when its socket never opens.
     */
    internal fun newPage(pageFactory: (devtoolsPort: Int, targetId: String, socketUrl: String) -> Page = ::Page): Page {
        val devtoolsPort = port()
        val request = Request.Builder()
            .url("http://127.0.0.1:$devtoolsPort/json/new?about:blank")
            .put(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        // Everything from the HTTP call through parsing is a conversation with the harness's
        // own browser, not with the extension's site.  Wrapping failures as
        // ChromeUnavailableException lets the diagnosis map them to UNSUPPORTED instead of
        // FAIL, which would blame the extension for an infrastructure gap.
        val target = try {
            HTTP.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ChromeUnavailableException("DevTools rejected a new page: HTTP ${response.code}")
                }
                json.parseToJsonElement(response.body.string()).jsonObject
            }
        } catch (e: ChromeUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw ChromeUnavailableException("could not create a DevTools target on port $devtoolsPort: ${e.message}", e)
        }

        val id = try {
            target.getValue("id").jsonPrimitive.content
        } catch (e: Exception) {
            throw ChromeUnavailableException("DevTools target creation answered without an id: $target", e)
        }
        val socketUrl = try {
            target.getValue("webSocketDebuggerUrl").jsonPrimitive.content
        } catch (e: Exception) {
            throw ChromeUnavailableException("DevTools target answered without a socket url: $target", e)
        }

        return try {
            pageFactory(devtoolsPort, id, socketUrl).also { registerTarget(id) }
        } catch (e: Throwable) {
            // The target was created before the constructor ran, so a failure there happens
            // before any close() could retire it. The browser-side target needs an explicit
            // close, or every failure leaves an orphan tab in a browser a long sweep keeps
            // running; for a socket that is still limbo (the 15s open timeout), that close is
            // also what makes the server drop the WebSocket and let OkHttp clean its side up.
            runCatching {
                val close = Request.Builder()
                    .url("http://127.0.0.1:$devtoolsPort/json/close/$id")
                    .build()
                HTTP.newCall(close).execute().close()
            }
            throw e
        }
    }

    private fun findBinary(): String {
        System.getenv(CHROME_ENV)?.takeIf { it.isNotBlank() }?.let { configured ->
            val file = File(configured)
            if (file.canExecute()) return file.absolutePath
            throw ChromeUnavailableException("$CHROME_ENV points at a non-executable file: $configured")
        }

        candidates.forEach { name ->
            val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .asSequence()
                .map { File(it, name) }
                .firstOrNull { it.canExecute() }
            if (found != null) return found.absolutePath
        }

        val known = listOf(
            "/usr/bin/google-chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/opt/google/chrome/chrome",
            System.getProperty("user.home") + "/.local/bin/google-chrome-stable",
        )
        known.firstOrNull { File(it).canExecute() }?.let { return it }

        throw ChromeUnavailableException(
            "no Chromium-based browser found; install one or set $CHROME_ENV to its path",
        )
    }

    fun stop() {
        process?.let { running ->
            runCatching { running.destroy() }
            if (!runCatching { running.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(true)) {
                runCatching { running.destroyForcibly() }
            }
        }
        process = null
        port = 0
        profileDir?.let { dir -> deleteProfile(dir) }
        profileDir = null
    }

    /**
     * Chromium keeps writing to its profile while it shuts down, so a single recursive delete can
     * lose the race and leave the directory behind.
     */
    private fun deleteProfile(dir: File) {
        repeat(5) {
            if (!dir.exists()) return
            dir.deleteRecursively()
            if (!dir.exists()) return
            Thread.sleep(100)
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }
}
