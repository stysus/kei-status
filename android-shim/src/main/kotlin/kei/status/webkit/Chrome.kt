package kei.status.webkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
                add("--headless=new")
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
                if (!line.isNullOrBlank()) {
                    port = line.trim().toInt()
                    return port
                }
                Thread.sleep(100)
            }
            throw ChromeUnavailableException("$binary did not open a DevTools port within 30s")
        }
    }

    /** Opens a blank page and connects a [Page] session to it. */
    fun newPage(): Page {
        val devtoolsPort = port()
        val request = Request.Builder()
            .url("http://127.0.0.1:$devtoolsPort/json/new?about:blank")
            .put(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        val target = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChromeUnavailableException("DevTools rejected a new page: HTTP ${response.code}")
            }
            json.parseToJsonElement(response.body.string()).jsonObject
        }

        val id = target.getValue("id").jsonPrimitive.content
        val socketUrl = target.getValue("webSocketDebuggerUrl").jsonPrimitive.content
        return Page(devtoolsPort, id, socketUrl)
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

/**
 * One DevTools page session, backed by a WebSocket.
 *
 * Commands are asynchronous: `WebView.loadUrl` runs on the main thread and must not block on the
 * browser, or the callbacks it is waiting for could never be delivered.
 */
class Page internal constructor(
    private val devtoolsPort: Int,
    internal val targetId: String,
    socketUrl: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val nextId = AtomicLong(1)

    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()

    private val listeners = ConcurrentHashMap<String, MutableList<(JsonObject) -> Unit>>()

    @Volatile
    private var closed = false

    private val socket: WebSocket = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
        .newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { dispatch(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed = true
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed = true
            }
        })

    fun on(event: String, listener: (JsonObject) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() } += listener
    }

    fun send(method: String, params: JsonObject = JsonObject(emptyMap())): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future

        val message = JsonObject(
            mapOf(
                "id" to kotlinx.serialization.json.JsonPrimitive(id),
                "method" to kotlinx.serialization.json.JsonPrimitive(method),
                "params" to params,
            ),
        )

        if (closed || !socket.send(message.toString())) {
            pending.remove(id)
            future.completeExceptionally(IllegalStateException("DevTools session is closed"))
        }
        return future
    }

    /** Sends [method] and blocks for its result. Never call this from the main thread. */
    fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        send(method, params).get(30, TimeUnit.SECONDS)

    private fun dispatch(text: String) {
        val message = json.parseToJsonElement(text).jsonObject

        message["id"]?.jsonPrimitive?.content?.toLongOrNull()?.let { id ->
            val future = pending.remove(id) ?: return
            val error = message["error"]?.jsonObject
            if (error != null) {
                future.completeExceptionally(
                    IllegalStateException(
                        error["message"]?.jsonPrimitive?.content ?: "DevTools error",
                    ),
                )
            } else {
                future.complete(message["result"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            return
        }

        val method = message["method"]?.jsonPrimitive?.content ?: return
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        listeners[method]?.toList()?.forEach { listener -> runCatching { listener(params) } }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close(1000, "done") }
        runCatching {
            val request = Request.Builder()
                .url("http://127.0.0.1:$devtoolsPort/json/close/$targetId")
                .build()
            OkHttpClient().newCall(request).execute().close()
        }
    }
}
