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

    var userAgentString: String? = null

    var supportMultipleWindows: Boolean = false

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
    }
}
