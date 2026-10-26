package kei.status.webkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Opens once the DevTools WebSocket is ready.
     *
     * `WebSocket.send` returns false before the socket is open and the command is lost, so a page
     * that is used immediately after creation would silently drop its first commands.
     */
    private val opened = CountDownLatch(1)

    @Volatile
    private var closed = false

    /** False once the socket failed or the target was retired by [close]. */
    val isOpen: Boolean get() = !closed

    // One client per page would carry its own Dispatcher and ConnectionPool, which outlive the
    // socket by their idle timeouts and pile up over a sweep of hundreds of pages. Deriving from
    // Chrome.HTTP shares those instead: newBuilder() copies the references, it does not rebuild
    // them. The read timeout is still cleared - websocket frames arrive on their own schedule,
    // not as an answer to a request - while the connect timeout is inherited unchanged.
    private val socket: WebSocket = Chrome.HTTP.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
        .newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { dispatch(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed = true
                opened.countDown()
                val wrapper = ChromeUnavailableException(
                    "DevTools socket to page $targetId failed: ${t.message}", t,
                )
                pending.values.forEach { it.completeExceptionally(wrapper) }
                pending.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed = true
            }
        })

    init {
        if (!opened.await(15, TimeUnit.SECONDS)) {
            throw ChromeUnavailableException("DevTools page $targetId did not open its socket within 15s")
        }
        if (closed) {
            throw ChromeUnavailableException("DevTools page $targetId closed while connecting")
        }
    }

    fun on(event: String, listener: (JsonObject) -> Unit) {
        listeners.computeIfAbsent(event) { mutableListOf() } += listener
    }

    fun send(method: String, params: JsonObject = JsonObject(emptyMap())): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future

        val message = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "method" to JsonPrimitive(method),
                "params" to params,
            ),
        )

        if (closed || !socket.send(message.toString())) {
            pending.remove(id)
            future.completeExceptionally(
                ChromeUnavailableException("DevTools session is closed"),
            )
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

    /**
     * Closes the page and retires its Chromium target.
     *
     * The HTTP target close is attempted even when the socket already failed. A WebSocket failure
     * marks [closed] without telling the browser anything - the flag describes the socket, never
     * the target - and a close that trusted it would leave an orphan tab in the browser for every
     * failure. Closing a target that is already gone only answers with an error, which the
     * runCatching swallows.
     */
    fun close() {
        val wasOpen = !closed
        closed = true
        if (wasOpen) runCatching { socket.close(1000, "done") }
        runCatching {
            val request = Request.Builder()
                .url("http://127.0.0.1:$devtoolsPort/json/close/$targetId")
                .build()
            Chrome.HTTP.newCall(request).execute().close()
        }
        Chrome.unregisterTarget(targetId)
    }
}
