package android.webkit

/**
 * Minimal stand-in for android.webkit.URLUtil.
 *
 * `isValidUrl` is the framework's own rule: the string carries one of the schemes WebView accepts.
 * Only that member is provided, because it is the only one extension code reaches.
 */
class URLUtil private constructor() {

    companion object {
        private val SCHEMES = listOf(
            "http://",
            "https://",
            "file:",
            "content:",
            "data:",
            "about:",
            "javascript:",
            "file:///android_asset/",
            "file:///android_res/",
        )

        @JvmStatic
        fun isValidUrl(url: String?): Boolean {
            if (url == null) return false
            return SCHEMES.any { url.regionMatches(0, it, 0, it.length, ignoreCase = true) }
        }
    }
}
