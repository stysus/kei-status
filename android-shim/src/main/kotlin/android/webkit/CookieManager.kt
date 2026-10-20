package android.webkit

/**
 * In-memory stand-in for android.webkit.CookieManager.
 *
 * Only storage is reproduced, which is all extensions use it for: `keiyoushi.network.Cookie` mirrors
 * cookies there and reads them back. No WebView is involved, so this is not an "Android feature we
 * cannot run" - it is a plain cookie store.
 *
 * Cookies set here are not shared with OkHttp's cookie jar; the harness relies on each layer
 * behaving as it does on Android, where they are also separate stores until the app syncs them.
 */
class CookieManager private constructor() {

    private val store = mutableMapOf<String, MutableMap<String, String>>()

    fun setCookie(url: String, value: String) {
        val host = hostOf(url) ?: return
        val jar = synchronized(store) { store.getOrPut(host) { mutableMapOf() } }
        parseCookies(value).forEach { (name, cookieValue) ->
            synchronized(jar) {
                if (cookieValue.isEmpty()) jar.remove(name) else jar[name] = cookieValue
            }
        }
    }

    fun getCookie(url: String): String? {
        val host = hostOf(url) ?: return null
        val jar = synchronized(store) { store[host]?.toMap() } ?: return null
        if (jar.isEmpty()) return null
        return jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    fun setAcceptCookie(accept: Boolean) = Unit

    fun acceptCookie(): Boolean = true

    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        synchronized(store) { store.clear() }
        callback?.onReceiveValue(true)
    }

    fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        removeAllCookies(callback)
    }

    fun flush() = Unit

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }

    /** `"a=1; Path=/; Domain=x"` -> `[a to 1]`, ignoring attributes. */
    private fun parseCookies(value: String): List<Pair<String, String>> =
        value.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filterNot { (name, _) ->
                name.lowercase() in setOf("path", "domain", "expires", "max-age", "samesite", "secure", "httponly")
            }

    companion object {
        private val instance = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = instance
    }
}
