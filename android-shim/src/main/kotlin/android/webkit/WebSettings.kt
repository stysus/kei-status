package android.webkit

/**
 * Stand-in for android.webkit.WebSettings.
 *
 * Values are recorded and applied to the browser when the page is created. `userAgentString` is
 * the only one that changes what the site sees in a way sources depend on; the rest are here so
 * configuration code runs unchanged.
 */
open class WebSettings internal constructor() {

    var javaScriptEnabled: Boolean = false

    var domStorageEnabled: Boolean = false

    var blockNetworkImage: Boolean = false

    var blockNetworkLoads: Boolean = false

    var useWideViewPort: Boolean = false

    var loadWithOverviewMode: Boolean = false

    /**
     * A UA the source asked for, applied to the page when it is created.
     *
     * Kept apart from [userAgentString] because reading that property must not turn into an
     * override: a source reads the current agent, derives a reduced one, and only then sets it.
     */
    internal var overrideUserAgent: String? = null

    /** What the browser reports for itself; filled in as soon as the page exists. */
    internal var browserUserAgent: String? = null

    /**
     * The agent the page sends, as on Android, where this is never null.
     *
     * `TurnstileHelper` reads it back to strip the `; wv` and `Version/4.0` markers a WebView
     * carries, so a null here fails the source before the captcha is even attempted.
     */
    var userAgentString: String
        get() = overrideUserAgent ?: browserUserAgent ?: DEFAULT_USER_AGENT
        set(value) {
            overrideUserAgent = value
        }

    @get:JvmName("supportMultipleWindows")
    var supportMultipleWindows: Boolean = false

    @get:JvmName("supportZoom")
    var supportZoom: Boolean = true

    var builtInZoomControls: Boolean = false

    var displayZoomControls: Boolean = false

    var allowFileAccess: Boolean = false

    var allowContentAccess: Boolean = false

    var databaseEnabled: Boolean = false

    var mediaPlaybackRequiresUserGesture: Boolean = true

    var mixedContentMode: Int = MIXED_CONTENT_NEVER_ALLOW

    var cacheMode: Int = LOAD_DEFAULT

    var loadsImagesAutomatically: Boolean = true

    var defaultTextEncodingName: String? = null

    var javaScriptCanOpenWindowsAutomatically: Boolean = false

    fun setGeolocationEnabled(enabled: Boolean) = Unit

    fun setAppCacheEnabled(enabled: Boolean) = Unit

    fun setAppCachePath(path: String?) = Unit

    fun setRenderPriority(priority: RenderPriority) = Unit

    enum class RenderPriority { NORMAL, HIGH, LOW }

    companion object {
        const val MIXED_CONTENT_ALWAYS_ALLOW: Int = 0
        const val MIXED_CONTENT_NEVER_ALLOW: Int = 1
        const val MIXED_CONTENT_COMPATIBILITY_MODE: Int = 2

        const val LOAD_DEFAULT: Int = -1
        const val LOAD_CACHE_ELSE_NETWORK: Int = 1
        const val LOAD_NO_CACHE: Int = 2
        const val LOAD_CACHE_ONLY: Int = 3

        /** Only read before a page exists; the browser's own agent replaces it. */
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
