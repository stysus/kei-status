package android.webkit

import android.net.Uri

/** Stand-in for android.webkit.WebResourceRequest. */
interface WebResourceRequest {
    fun getUrl(): Uri
    fun isForMainFrame(): Boolean
    fun hasGesture(): Boolean
    fun isRedirect(): Boolean
    fun getMethod(): String
    fun getRequestHeaders(): Map<String, String>
}

internal class ResourceRequest(
    private val url: Uri,
    private val mainFrame: Boolean,
    private val method: String,
    private val headers: Map<String, String>,
) : WebResourceRequest {
    override fun getUrl(): Uri = url
    override fun isForMainFrame(): Boolean = mainFrame
    override fun hasGesture(): Boolean = false
    override fun isRedirect(): Boolean = false
    override fun getMethod(): String = method
    override fun getRequestHeaders(): Map<String, String> = headers
}

/** Stand-in for android.webkit.WebResourceError. */
class WebResourceError internal constructor(
    private val errorCode: Int,
    private val description: CharSequence,
) {
    fun getErrorCode(): Int = errorCode
    fun getDescription(): CharSequence = description

    companion object {
        const val ERROR_UNKNOWN: Int = -1
        const val ERROR_HOST_LOOKUP: Int = -2
        const val ERROR_CONNECT: Int = -6
        const val ERROR_TIMEOUT: Int = -8
        const val ERROR_FAILED_SSL_HANDSHAKE: Int = -11
        const val ERROR_BAD_URL: Int = -12
        const val ERROR_UNSAFE_RESOURCE: Int = -16
    }
}

/** Stand-in for android.webkit.RenderProcessGoneDetail. */
class RenderProcessGoneDetail internal constructor(private val crashed: Boolean) {
    fun didCrash(): Boolean = crashed
}

/** Stand-in for android.webkit.ConsoleMessage. */
class ConsoleMessage internal constructor(
    private val message: String,
    private val sourceId: String,
    private val lineNumber: Int,
    private val level: MessageLevel,
) {
    fun message(): String = message
    fun sourceId(): String = sourceId
    fun lineNumber(): Int = lineNumber
    fun messageLevel(): MessageLevel = level

    enum class MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }
}

/** Marks a method exposed to page JavaScript, as android.webkit.JavascriptInterface does. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface
