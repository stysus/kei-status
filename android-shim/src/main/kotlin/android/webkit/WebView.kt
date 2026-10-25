package android.webkit

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kei.status.webkit.ChallengeSolver
import kei.status.webkit.Chrome
import kei.status.webkit.Page
import kei.status.webkit.autoSolveEnabled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** Only used to stamp trace lines; the harness itself never depends on wall-clock timing. */
    private val pageStartedNanos = System.nanoTime()

    private fun sinceStartMs(): Long = (System.nanoTime() - pageStartedNanos) / 1_000_000

    private var currentUrl: String? = null

    private var mainFrameId: String? = null

    private var pendingDocument: PendingDocument? = null

    /**
     * Extra headers for the next top-level navigation, as passed to
     * `WebView.loadUrl(url, additionalHttpHeaders)`.
     *
     * Android applies these to the main-frame request only. They are attached per request here
     * because Chromium's `Network.setExtraHTTPHeaders` would also put them on every subresource,
     * and a custom header on a cross-origin `fetch()` turns it into a CORS preflight the site may
     * not answer.
     */
    private var navigationHeaders: Map<String, String> = emptyMap()

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
        navigationHeaders = additionalHttpHeaders
        startCookiePolling()
        if (additionalHttpHeaders.isNotEmpty()) {
            trace("loadUrl $url headers=${additionalHttpHeaders.keys}")
        }

        // Navigation waits for the session's interception to be armed. Issuing it alongside
        // `Fetch.enable` lets the document escape to the network, which for `loadDataWithBaseURL`
        // means the served HTML is replaced by a DNS error page.
        afterSetup {
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
        navigationHeaders = emptyMap()
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

    // region input

    /** Stand-in for android.webkit.WebView.VisualStateCallback. */
    abstract class VisualStateCallback {
        abstract fun onComplete(requestId: Long)
    }

    /**
     * Reports the page's next frame, as Android reports the WebView's next draw.
     *
     * `Comix` waits for this before sending the touches that answer a captcha, because a touch that
     * arrives before the page has painted is dropped. A page that never paints (or a browser that
     * throttles it) leaves the callback pending; sources that need it arm their own fallback, which
     * is why this does not invent a timeout.
     */
    fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {
        val session = page
        if (session == null || destroyed) {
            mainHandler.post { if (!destroyed) callback.onComplete(requestId) }
            return
        }

        session.send(
            "Runtime.evaluate",
            JsonObject(
                mapOf(
                    "expression" to JsonPrimitive(NEXT_FRAME),
                    "awaitPromise" to JsonPrimitive(true),
                    "returnByValue" to JsonPrimitive(true),
                ),
            ),
        ).whenComplete { _, _ ->
            mainHandler.post { if (!destroyed) callback.onComplete(requestId) }
        }
    }

    /**
     * Delivers a synthetic touch to the page, which is how `Comix` answers a captcha's swipe.
     *
     * Android hands the WebView touch coordinates in physical pixels while the browser takes CSS
     * pixels, and one CSS pixel is one dp in a WebView, so the density is what converts between
     * them. Sources already did the opposite conversion to build the event.
     */
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val session = page ?: return false

        val type = when (event.action) {
            MotionEvent.ACTION_DOWN -> "touchStart"
            MotionEvent.ACTION_MOVE -> "touchMove"
            MotionEvent.ACTION_UP -> "touchEnd"
            MotionEvent.ACTION_CANCEL -> "touchCancel"
            else -> return false
        }

        val density = getResources().displayMetrics.density.takeIf { it > 0f } ?: 1f
        val points = (0 until event.getPointerCount()).map { index ->
            val point = mutableMapOf<String, JsonPrimitive>(
                "x" to JsonPrimitive(event.getX(index) / density),
                "y" to JsonPrimitive(event.getY(index) / density),
                "id" to JsonPrimitive(event.getPointerId(index)),
                "force" to JsonPrimitive(event.getPressure(index)),
            )
            // Chromium wants radii, Android reports the contact's diameter
            val major = event.getTouchMajor(index) / 2 / density
            val minor = event.getTouchMinor(index) / 2 / density
            if (major > 0f) point["radiusX"] = JsonPrimitive(major)
            if (minor > 0f) point["radiusY"] = JsonPrimitive(minor)
            JsonObject(point)
        }

        session.send(
            "Input.dispatchTouchEvent",
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(type),
                    "touchPoints" to JsonArray(points),
                ),
            ),
        ).whenComplete { _, error ->
            if (error != null) trace("dispatchTouchEvent $type failed: ${error.message}")
        }
        return true
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
            navigationHeaders = emptyMap()
            syncCookiesFromBrowser()
            armChallengeSolver(session)
            val url = currentUrl.orEmpty()
            mainHandler.post {
                if (!destroyed) webViewClient.onPageFinished(this, url)
            }
        }

        session.on("Network.loadingFailed") { params ->
            val type = params["type"]?.jsonPrimitive?.content.orEmpty()
            if (type != "Document") {
                if (TRACE) {
                    val error = params["errorText"]?.jsonPrimitive?.content.orEmpty()
                    val blocked = params["blockedReason"]?.jsonPrimitive?.content
                    trace("loadingFailed $type $error blocked=$blocked")
                }
                return@on
            }
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

        session.on("Network.responseReceived") { params ->
            if (!TRACE) return@on
            val response = params["response"]?.jsonObject ?: return@on
            val status = response["status"]?.jsonPrimitive?.content.orEmpty()
            trace("response +${sinceStartMs()}ms $status ${response["url"]?.jsonPrimitive?.content.orEmpty()}")
        }

        session.on("Network.loadingFinished") { params ->
            if (!TRACE) return@on
            val length = params["encodedDataLength"]?.jsonPrimitive?.content.orEmpty()
            trace("finished +${sinceStartMs()}ms $length ${params["requestId"]?.jsonPrimitive?.content.orEmpty()}")
        }

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

        settings.overrideUserAgent?.let { agent ->
            setup += session.send(
                "Network.setUserAgentOverride",
                JsonObject(mapOf("userAgent" to JsonPrimitive(agent))),
            )
        }

        // Read the agent the page will actually send, so `settings.userAgentString` reports it the
        // way Android does instead of guessing. Sources that derive a reduced agent from it need the
        // real Chrome version.
        setup += session.send(
            "Runtime.evaluate",
            JsonObject(
                mapOf(
                    "expression" to JsonPrimitive("navigator.userAgent"),
                    "returnByValue" to JsonPrimitive(true),
                ),
            ),
        ).thenAccept { result ->
            result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                ?.let { settings.browserUserAgent = it }
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

        trace("requestPaused +${sinceStartMs()}ms $resourceType $method $url")

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
            val extraHeaders = if (resourceType == "Document" && frameId != null && frameId == mainFrameId) {
                navigationHeaders
            } else {
                emptyMap()
            }
            continueRequest(session, requestId, request, extraHeaders)
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
     * The request body has to be handed back explicitly: `Fetch.continueRequest` without a body
     * resumes a POST with an empty one, which silently breaks every GraphQL/form request the page
     * makes. `postDataEntries` is preferred over `postData` because it is the browser's own bytes,
     * base64-encoded; re-encoding the decoded text is not byte-identical for every body, and a
     * body Chromium considers changed is aborted at the network layer - Comikey's Firebase App
     * Check exchange came back empty that way and left its reader waiting for a token that never
     * arrived.
     *
     * `extraHeaders` is merged over the browser's own headers, because `continueRequest` replaces
     * the header list outright when it is given one.
     */
    private fun continueRequest(
        session: Page,
        requestId: String,
        request: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val postDataEntries = request["postDataEntries"]?.jsonArray
        val postData = request["postData"]?.jsonPrimitive?.content
        val params = buildMap {
            put("requestId", JsonPrimitive(requestId))
            if (postDataEntries != null && postDataEntries.isNotEmpty()) {
                put("postDataEntries", postDataEntries)
            } else if (postData != null) {
                put("postData", JsonPrimitive(postData))
            }
            if (extraHeaders.isNotEmpty()) {
                val headers = request["headers"]?.jsonObject
                    ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                    .orEmpty() + extraHeaders
                put(
                    "headers",
                    JsonArray(
                        headers.map { (name, value) ->
                            JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(name),
                                    "value" to JsonPrimitive(value),
                                ),
                            )
                        },
                    ),
                )
            }
        }
        session.send("Fetch.continueRequest", JsonObject(params))
        trace("continued +${sinceStartMs()}ms $requestId")
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
     * Mirrors the browser's cookies for the current page into [CookieManager].
     *
     * Sources solve a challenge inside the page and then read the resulting cookie from
     * `CookieManager` to reuse it on OkHttp requests. Polling is the only way to see cookies set
     * by JavaScript, which produce no network response to listen to.
     *
     * The fetch is scoped to the current page's URL rather than the whole profile: one Chromium
     * serves the whole sweep, so `getAllCookies` would round-trip every site's cookies — hundreds
     * by mid-shard — four times a second per live WebView, and would copy cookies this source's
     * WebView never touched into the shared jar.
     */
    private fun syncCookiesFromBrowser() {
        val session = page ?: return
        val url = currentUrl ?: return
        session.send(
            "Network.getCookies",
            JsonObject(mapOf("urls" to JsonArray(listOf(JsonPrimitive(url))))),
        ).whenComplete { result, error ->
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

        /** Resolves after the browser has committed the page's next frame. */
        private const val NEXT_FRAME = "new Promise(resolve => requestAnimationFrame(() => resolve(1)))"

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
