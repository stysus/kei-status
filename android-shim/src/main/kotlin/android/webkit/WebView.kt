package android.webkit

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import kei.status.webkit.ChallengeSolver
import kei.status.webkit.Chrome
import kei.status.webkit.Page
import kei.status.webkit.autoSolveEnabled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Stand-in for android.webkit.WebView, driven by a real headless Chromium over the DevTools
 * protocol.
 *
 * Sources use `runWebView` to run a site's own JavaScript: challenge solvers that set cookies,
 * SPA readers that scrape the DOM, `localStorage` probes. None of that can be faked with a DOM
 * emulation, so each WebView owns one Chromium page. Callbacks are delivered on the main thread,
 * as on Android, and every command is asynchronous so the main thread is never blocked waiting
 * for the browser.
 */
open class WebView(context: Context?) : View() {

    val settings: WebSettings = WebSettings()

    var webViewClient: WebViewClient = WebViewClient()

    var webChromeClient: WebChromeClient? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var page: Page? = null

    private var destroyed = false

    private var currentUrl: String? = null

    private var mainFrameId: String? = null

    private var pendingDocument: PendingDocument? = null

    private val bindings = LinkedHashMap<String, Any>()

    private val bindingScripts = mutableListOf<String>()

    private var cookiePollerStarted = false

    /** Incremented per page load so a watcher from an earlier load stops instead of interfering. */
    @Volatile
    private var challengeGeneration = 0

    private var ready: java.util.concurrent.CompletableFuture<*>? = null

    init {
        this.context = context
    }

    // region navigation

    fun loadUrl(url: String) = loadUrl(url, emptyMap())

    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        val session = ensurePage()
        currentUrl = url
        pendingDocument = null
        startCookiePolling()

        // Navigation waits for the session's interception to be armed. Issuing it alongside
        // `Fetch.enable` lets the document escape to the network, which for `loadDataWithBaseURL`
        // means the served HTML is replaced by a DNS error page.
        afterSetup {
            if (additionalHttpHeaders.isNotEmpty()) {
                session.send(
                    "Network.setExtraHTTPHeaders",
                    JsonObject(mapOf("headers" to JsonObject(additionalHttpHeaders.mapValues { JsonPrimitive(it.value) }))),
                )
            }
            pushCookiesToBrowser(url)
            navigate(session, url)
        }
    }

    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        val session = ensurePage()
        val origin = baseUrl ?: "about:blank"
        currentUrl = origin
        startCookiePolling()

        // The document is served by intercepting the first navigation instead of by fetching the
        // URL, so `baseUrl` becomes the page's origin (localStorage, cookies) without a request.
        pendingDocument = PendingDocument(origin, data, mimeType ?: "text/html", encoding ?: "UTF-8")
        afterSetup {
            navigate(session, origin)
        }
    }

    fun loadData(data: String, mimeType: String?, encoding: String?) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    fun reload() {
        val url = currentUrl ?: return
        ensurePage().send("Page.reload", JsonObject(mapOf("url" to JsonPrimitive(url))))
    }

    fun stopLoading() {
        page?.send("Page.stopLoading")
    }

    fun getUrl(): String? = currentUrl

    fun canGoBack(): Boolean = false

    fun canGoForward(): Boolean = false

    fun clearCache(includeDiskFiles: Boolean) {
        page?.send("Network.clearBrowserCache")
    }

    fun clearHistory() = Unit

    fun setInitialScale(scaleInPercent: Int) = Unit

    // endregion

    // region javascript

    fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
        val session = page
        if (session == null || destroyed) {
            callback?.onReceiveValue("null")
            return
        }

        session.send(
            "Runtime.evaluate",
            JsonObject(
                mapOf(
                    "expression" to JsonPrimitive(script),
                    "returnByValue" to JsonPrimitive(true),
                    "awaitPromise" to JsonPrimitive(true),
                    "userGesture" to JsonPrimitive(true),
                    "allowUnsafeEvalBlockedByCSP" to JsonPrimitive(true),
                ),
            ),
        ).whenComplete { result, error ->
            // Android hands the callback the JSON encoding of the value, or the string "null"
            // for undefined/errors. Match that so callers can keep using parseAs<String?>.
            val value = if (error != null) {
                "null"
            } else {
                result?.get("result")?.jsonObject?.get("value")?.toString() ?: "null"
            }
            if (TRACE) {
                val exception = result?.get("exceptionDetails")?.jsonObject
                    ?.let { it["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: it["text"]?.jsonPrimitive?.content }
                trace("evaluate ${script.replace('\n', ' ').take(100)} -> ${value.take(160)}" + (exception?.let { " EXCEPTION $it" } ?: ""))
            }
            mainHandler.post {
                if (!destroyed) callback?.onReceiveValue(value)
            }
        }
    }

    fun addJavascriptInterface(obj: Any, name: String) {
        bindings[name] = obj
        val script = buildBridgeScript(name, obj)
        bindingScripts += script

        val session = page ?: return
        // Applies to later navigations, matching Android's documented behaviour.
        session.send(
            "Page.addScriptToEvaluateOnNewDocument",
            JsonObject(mapOf("source" to JsonPrimitive(script))),
        )
    }

    fun removeJavascriptInterface(name: String) {
        bindings.remove(name)
    }

    // endregion

    fun destroy() {
        if (destroyed) return
        destroyed = true
        val session = page
        page = null
        session?.close()
    }

    // region internals

    private class PendingDocument(
        val baseUrl: String,
        val html: String,
        val mimeType: String,
        val encoding: String,
    )

    private fun ensurePage(): Page {
        page?.let { return it }

        val session = Chrome.newPage()
        page = session
        trace("page ${session.targetId} created")

        session.on("Page.frameNavigated") { params ->
            val frame = params["frame"]?.jsonObject ?: return@on
            if (frame["parentId"] != null) return@on
            mainFrameId = frame["id"]?.jsonPrimitive?.content
            val url = frame["url"]?.jsonPrimitive?.content.orEmpty()
            currentUrl = url
            trace("frameNavigated $url")
            mainHandler.post {
                if (!destroyed) webViewClient.onPageStarted(this, url, null)
            }
        }

        session.on("Page.loadEventFired") {
            trace("loadEventFired ${currentUrl.orEmpty()}")
            syncCookiesFromBrowser()
            armChallengeSolver(session)
            val url = currentUrl.orEmpty()
            mainHandler.post {
                if (!destroyed) webViewClient.onPageFinished(this, url)
            }
        }

        session.on("Network.loadingFailed") { params ->
            if (params["type"]?.jsonPrimitive?.content != "Document") return@on
            if (params["frameId"]?.jsonPrimitive?.content != mainFrameId) return@on
            val description = params["errorText"]?.jsonPrimitive?.content ?: "net::ERR_FAILED"
            val url = currentUrl.orEmpty()
            val request = ResourceRequest(Uri.parse(url), true, "GET", emptyMap())
            val error = WebResourceError(WebResourceError.ERROR_UNKNOWN, description)
            mainHandler.post {
                if (!destroyed) webViewClient.onReceivedError(this, request, error)
            }
        }

        session.on("Fetch.requestPaused") { params -> onRequestPaused(session, params) }

        session.on("Runtime.bindingCalled") { params ->
            val payload = params["payload"]?.jsonPrimitive?.content ?: return@on
            trace("bindingCalled ${payload.take(120)}")
            dispatchBridgeCall(payload)
        }

        session.on("Runtime.consoleAPICalled") { params ->
            val client = webChromeClient ?: return@on
            val level = params["type"]?.jsonPrimitive?.content.orEmpty()
            val message = params["args"]?.jsonArray
                ?.joinToString(" ") { argument ->
                    val element = argument.jsonObject
                    element["value"]?.toString()?.trim('"')
                        ?: element["description"]?.jsonPrimitive?.content
                        ?: element["type"]?.jsonPrimitive?.content.orEmpty()
                }
                .orEmpty()
            val consoleMessage = ConsoleMessage(
                message,
                "",
                0,
                when (level) {
                    "error" -> ConsoleMessage.MessageLevel.ERROR
                    "warning" -> ConsoleMessage.MessageLevel.WARNING
                    "debug" -> ConsoleMessage.MessageLevel.DEBUG
                    else -> ConsoleMessage.MessageLevel.LOG
                },
            )
            mainHandler.post {
                if (!destroyed) client.onConsoleMessage(consoleMessage)
            }
        }

        // Uncaught page errors are the single most useful signal when a source's JavaScript fails,
        // and Android surfaces them through the same console channel.
        session.on("Runtime.exceptionThrown") { params ->
            val client = webChromeClient ?: return@on
            val details = params["exceptionDetails"]?.jsonObject ?: return@on
            val text = details["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content
                ?: details["text"]?.jsonPrimitive?.content
                ?: return@on
            val url = details["url"]?.jsonPrimitive?.content.orEmpty()
            val line = details["lineNumber"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val consoleMessage = ConsoleMessage(
                text,
                url,
                line,
                ConsoleMessage.MessageLevel.ERROR,
            )
            mainHandler.post {
                if (!destroyed) client.onConsoleMessage(consoleMessage)
            }
        }

        val setup = mutableListOf<java.util.concurrent.CompletableFuture<*>>()
        setup += session.send("Page.enable")
        setup += session.send("Network.enable")
        setup += session.send("Runtime.enable")
        setup += session.send("DOM.enable")
        setup += session.send("Runtime.addBinding", JsonObject(mapOf("name" to JsonPrimitive(BRIDGE_BINDING))))
        setup += session.send(
            "Fetch.enable",
            JsonObject(
                mapOf(
                    "patterns" to JsonArray(listOf(JsonObject(mapOf("urlPattern" to JsonPrimitive("*"))))),
                ),
            ),
        )

        settings.userAgentString?.let { agent ->
            setup += session.send(
                "Network.setUserAgentOverride",
                JsonObject(mapOf("userAgent" to JsonPrimitive(agent))),
            )
        }

        bindingScripts.forEach { script ->
            setup += session.send(
                "Page.addScriptToEvaluateOnNewDocument",
                JsonObject(mapOf("source" to JsonPrimitive(script))),
            )
        }

        // Armed before any navigation so the challenge's readiness message cannot be missed.
        if (autoSolveEnabled) {
            setup += session.send(
                "Page.addScriptToEvaluateOnNewDocument",
                JsonObject(mapOf("source" to JsonPrimitive(ChallengeSolver.watcherScript))),
            )
        }

        ready = java.util.concurrent.CompletableFuture.allOf(*setup.toTypedArray())

        startCookiePolling()

        return session
    }

    /** Runs [block] once the session's interception and bindings are armed. */
    private fun afterSetup(block: () -> Unit) {
        val pending = ready
        if (pending == null) {
            block()
            return
        }
        pending.whenComplete { _, _ -> block() }
    }

    /**
     * Navigates, retrying a navigation the browser dropped.
     *
     * A target created through the DevTools HTTP endpoint drops its first navigation with
     * `net::ERR_ABORTED` while it is still attaching, which would leave a blank page reporting
     * nothing. The abort is reported by `Page.navigate` itself, so it can be retried without
     * guessing; a navigation superseded by a later call is left alone.
     */
    private fun navigate(session: Page, url: String, attempt: Int = 0) {
        session.send("Page.navigate", JsonObject(mapOf("url" to JsonPrimitive(url))))
            .whenComplete { result, _ ->
                val aborted = result?.get("errorText")?.jsonPrimitive?.content == "net::ERR_ABORTED"
                if (!aborted || attempt >= NAVIGATE_RETRIES || destroyed) return@whenComplete
                if (currentUrl != url) return@whenComplete
                trace("navigation aborted; retrying ${attempt + 1}")
                mainHandler.postDelayed({ navigate(session, url, attempt + 1) }, NAVIGATE_RETRY_MS)
            }
    }

    private fun onRequestPaused(session: Page, params: JsonObject) {
        val requestId = params["requestId"]?.jsonPrimitive?.content ?: return
        val request = params["request"]?.jsonObject ?: return
        val url = request["url"]?.jsonPrimitive?.content ?: return
        val method = request["method"]?.jsonPrimitive?.content ?: "GET"
        val headers = request["headers"]?.jsonObject
            ?.mapValues { (_, value) -> value.jsonPrimitive.content }
            .orEmpty()
        val resourceType = params["resourceType"]?.jsonPrimitive?.content.orEmpty()
        val frameId = params["frameId"]?.jsonPrimitive?.content

        trace("requestPaused $resourceType $url")

        if (settings.blockNetworkImage && resourceType == "Image") {
            session.send("Fetch.failRequest", JsonObject(mapOf(
                "requestId" to JsonPrimitive(requestId),
                "errorReason" to JsonPrimitive("BlockedByClient"),
            )))
            return
        }

        val document = pendingDocument
        if (document != null && resourceType == "Document" && url == document.baseUrl) {
            // Not cleared here: the browser can abort and retry this navigation, and the retry
            // must be served the same document rather than falling through to the network.
            fulfill(
                session,
                requestId,
                200,
                document.mimeType,
                document.encoding,
                document.html.toByteArray(),
            )
            return
        }

        val webRequest = ResourceRequest(
            Uri.parse(url),
            frameId != null && frameId == mainFrameId,
            method,
            headers,
        )

        val response = try {
            webViewClient.shouldInterceptRequest(this, webRequest)
        } catch (e: Throwable) {
            null
        }

        if (response == null) {
            continueRequest(session, requestId, request)
            return
        }

        val body = try {
            response.getData()?.readBytes() ?: ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        }
        fulfill(
            session,
            requestId,
            response.getStatusCode().takeIf { it > 0 } ?: 200,
            response.getMimeType() ?: "application/octet-stream",
            response.getEncoding() ?: "utf-8",
            body,
            response.getResponseHeaders(),
        )
    }

    private fun fulfill(
        session: Page,
        requestId: String,
        status: Int,
        mimeType: String,
        encoding: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val headers = buildList {
            add(JsonObject(mapOf(
                "name" to JsonPrimitive("Content-Type"),
                "value" to JsonPrimitive("$mimeType; charset=$encoding"),
            )))
            add(JsonObject(mapOf(
                "name" to JsonPrimitive("Content-Length"),
                "value" to JsonPrimitive(body.size.toString()),
            )))
            extraHeaders.forEach { (name, value) ->
                if (!name.equals("Content-Type", ignoreCase = true)) {
                    add(JsonObject(mapOf(
                        "name" to JsonPrimitive(name),
                        "value" to JsonPrimitive(value),
                    )))
                }
            }
        }

        session.send(
            "Fetch.fulfillRequest",
            JsonObject(
                mapOf(
                    "requestId" to JsonPrimitive(requestId),
                    "responseCode" to JsonPrimitive(status),
                    "responseHeaders" to JsonArray(headers),
                    "body" to JsonPrimitive(Base64.getEncoder().encodeToString(body)),
                ),
            ),
        )
    }

    /**
     * Resumes a paused request.
     *
     * The request body has to be handed back explicitly: `Fetch.continueRequest` without
     * `postData` resumes a POST with an empty body, which silently breaks every GraphQL/form
     * request the page makes.
     */
    private fun continueRequest(session: Page, requestId: String, request: JsonObject) {
        val postData = request["postData"]?.jsonPrimitive?.content
        val params = buildMap {
            put("requestId", JsonPrimitive(requestId))
            if (postData != null) put("postData", JsonPrimitive(postData))
        }
        session.send("Fetch.continueRequest", JsonObject(params))
    }

    private fun buildBridgeScript(name: String, obj: Any): String {
        val methods = obj.javaClass.methods
            .filter { it.declaringClass != Any::class.java && it.parameterCount <= 1 }
            .map { it.name }
            .distinct()

        val functions = methods.joinToString(",\n") { method ->
            """  $method: function (a) { window.__keiStatusDispatch(${name.toJsString()}, ${method.toJsString()}, a); }"""
        }

        return """
            (function () {
              if (!window.__keiStatusDispatch) {
                window.__keiStatusDispatch = function (i, m, a) {
                  try { $BRIDGE_BINDING(JSON.stringify({ i: i, m: m, a: a === undefined ? "" : String(a) })); } catch (e) {}
                };
              }
              window[${name.toJsString()}] = {
            $functions
              };
            })();
        """.trimIndent()
    }

    private fun dispatchBridgeCall(payload: String) {
        val call = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        val interfaceName = call["i"]?.jsonPrimitive?.content ?: return
        val methodName = call["m"]?.jsonPrimitive?.content ?: return
        val argument = call["a"]?.jsonPrimitive?.content ?: ""
        val target = bindings[interfaceName] ?: return

        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount <= 1
        } ?: return

        mainHandler.post {
            if (destroyed) return@post
            runCatching {
                if (method.parameterCount == 0) method.invoke(target) else method.invoke(target, argument)
            }
        }
    }

    private fun String.toJsString(): String = JsonPrimitive(this).toString()

    // endregion

    // region challenge

    /**
     * Clicks Cloudflare's checkbox, but only once the page has proven to be a challenge.
     *
     * This is a fallback for sources that open a WebView at a challenged URL themselves; the
     * interceptor handles the ordinary case. A page that merely loads is never clicked, so an
     * ordinary manga page cannot be touched by accident.
     *
     * Each load arms its own watcher. The generation counter makes a watcher from an earlier load
     * go quiet instead of clearing state that a later load owns, which matters because a challenge
     * page reloads itself while it runs.
     */
    private fun armChallengeSolver(session: Page) {
        if (!autoSolveEnabled) return
        val generation = ++challengeGeneration
        ChallengeSolver.probeAsync(session) { probe ->
            if (!probe.isChallenge) return@probeAsync
            mainHandler.post {
                if (destroyed || generation != challengeGeneration) return@post
                trace("challenge detected; watching for the widget")
                mainHandler.postDelayed(challengeWatcher(generation), CHALLENGE_POLL_MS)
            }
        }
    }

    private fun challengeWatcher(generation: Int): Runnable {
        val clicked = mutableSetOf<String>()
        return object : Runnable {
            override fun run() {
                if (destroyed || generation != challengeGeneration) return
                val session = page ?: return
                ChallengeSolver.probeAsync(session) { probe ->
                    mainHandler.post {
                        if (destroyed || generation != challengeGeneration) return@post
                        if (!probe.isChallenge) {
                            trace("challenge cleared")
                            return@post
                        }
                        // `clickAt` is only reported once the widget accepts input, so a click is
                        // never sent into a half-built widget. Cloudflare issues a fresh widget id
                        // when it replaces a rejected one, so remembering the id clicks each widget
                        // exactly once.
                        val key = probe.widgetKey
                        val point = probe.clickAt
                        if (key != null && point != null && clicked.add(key)) {
                            trace("clicking challenge widget '$key' at ${point.x},${point.y}")
                            ChallengeSolver.click(session, point)
                        }
                        mainHandler.postDelayed(this, CHALLENGE_POLL_MS)
                    }
                }
            }
        }
    }

    // endregion

    // region cookies

    private fun pushCookiesToBrowser(url: String) {
        val header = CookieManager.getInstance().getCookie(url) ?: return
        val session = page ?: return

        header.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
            session.send(
                "Network.setCookie",
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(pair.substringBefore('=')),
                        "value" to JsonPrimitive(pair.substringAfter('=')),
                        "url" to JsonPrimitive(url),
                    ),
                ),
            )
        }
    }

    private fun startCookiePolling() {
        if (cookiePollerStarted) return
        cookiePollerStarted = true
        mainHandler.postDelayed(cookiePoller, COOKIE_POLL_MS)
    }

    private val cookiePoller = object : Runnable {
        override fun run() {
            if (destroyed) return
            syncCookiesFromBrowser()
            mainHandler.postDelayed(this, COOKIE_POLL_MS)
        }
    }

    /**
     * Mirrors the browser's cookie jar into [CookieManager].
     *
     * Sources solve a challenge inside the page and then read the resulting cookie from
     * `CookieManager` to reuse it on OkHttp requests. Polling is the only way to see cookies set
     * by JavaScript, which produce no network response to listen to.
     */
    private fun syncCookiesFromBrowser() {
        val session = page ?: return
        session.send("Network.getAllCookies").whenComplete { result, error ->
            if (error != null || result == null) return@whenComplete
            val cookies = result["cookies"] as? JsonArray ?: return@whenComplete
            cookies.forEach { element ->
                val cookie = element.jsonObject
                val name = cookie["name"]?.jsonPrimitive?.content ?: return@forEach
                val value = cookie["value"]?.jsonPrimitive?.content ?: ""
                val domain = cookie["domain"]?.jsonPrimitive?.content?.removePrefix(".") ?: return@forEach
                val path = cookie["path"]?.jsonPrimitive?.content ?: "/"
                CookieManager.getInstance().setCookie("https://$domain$path", "$name=$value")
            }
        }
    }

    // endregion

    companion object {
        private const val BRIDGE_BINDING = "__keiStatusBridge"
        private const val COOKIE_POLL_MS = 250L
        private const val CHALLENGE_POLL_MS = 500L
        private const val NAVIGATE_RETRIES = 3
        private const val NAVIGATE_RETRY_MS = 150L

        /** Set KEI_STATUS_WEBVIEW_TRACE=1 to log every DevTools event to stderr. */
        internal val TRACE = System.getenv("KEI_STATUS_WEBVIEW_TRACE") != null

        internal fun trace(message: String) {
            if (TRACE) System.err.println("[webview] $message")
        }

        @JvmStatic
        fun getWebViewClassLoader(): ClassLoader = WebView::class.java.classLoader!!

        @JvmStatic
        fun setWebContentsDebuggingEnabled(enabled: Boolean) = Unit
    }
}
