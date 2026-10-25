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

    // Each entry maps cookie-name to its value and the domain it belongs to. A null domain means
    // the cookie is host-only (no Domain= attribute was present in the Set-Cookie header).
    private val store = mutableMapOf<String, MutableMap<String, CookieEntry>>()

    private data class CookieEntry(val value: String, val domain: String?)

    fun setCookie(url: String, value: String) {
        val host = hostOf(url) ?: return
        val domain = parseDomain(value)
        val jar = synchronized(store) { store.getOrPut(host) { mutableMapOf() } }
        parseCookies(value).forEach { (name, cookieValue) ->
            synchronized(jar) {
                if (cookieValue.isEmpty()) jar.remove(name) else jar[name] = CookieEntry(cookieValue, domain)
            }
        }
    }

    fun getCookie(url: String): String? {
        val host = hostOf(url) ?: return null

        // Walk every bucket: a cookie stored under "example.com" with domain "example.com"
        // matches a request to "api.example.com", the same way a browser sends it.
        val result = mutableMapOf<String, String>()
        synchronized(store) {
            for ((bucketHost, jar) in store) {
                synchronized(jar) {
                    for ((name, entry) in jar) {
                        if (entry.domain != null) {
                            // Domain cookie: matches the domain itself and any subdomain.
                            if (host == entry.domain || host.endsWith(".${entry.domain}")) {
                                result[name] = entry.value
                            }
                        } else {
                            // Host-only cookie: matches only the exact host it was stored under.
                            if (host == bucketHost) {
                                result[name] = entry.value
                            }
                        }
                    }
                }
            }
        }

        if (result.isEmpty()) return null
        return result.entries.joinToString("; ") { (name, value) -> "$name=$value" }
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

    /**
     * Extracts the `Domain` attribute from a Set-Cookie header value, or null if absent.
     *
     * A leading dot is stripped: RFC 6265 §5.2.3 says "If the first character of the
     * attribute-value string is %x2E ("."), remove it." The result is the bare domain the
     * cookie applies to.
     */
    private fun parseDomain(header: String): String? =
        header.split(';')
            .map { it.trim() }
            .firstOrNull { it.lowercase().startsWith("domain=") }
            ?.substringAfter('=')
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase()
            ?.ifEmpty { null }

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
