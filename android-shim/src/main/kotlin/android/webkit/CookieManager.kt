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
        val parsed = parseCookieHeader(value)
        val bucket = parsed.domain ?: host
        val jar = synchronized(store) { store.getOrPut(bucket) { mutableMapOf() } }
        parsed.cookies.forEach { (name, cookieValue) ->
            synchronized(jar) {
                if (cookieValue.isEmpty()) jar.remove(name) else jar[name] = CookieEntry(cookieValue, parsed.domain)
            }
        }
    }

    fun getCookie(url: String): String? {
        val host = hostOf(url) ?: return null
        val result = mutableMapOf<String, String>()

        // Host-only cookies: only the exact host bucket.
        synchronized(store) { store[host] }?.let { jar ->
            synchronized(jar) {
                for ((name, entry) in jar) {
                    if (entry.domain == null) result[name] = entry.value
                }
            }
        }

        // Domain cookies: walk the host and its parents, checking each bucket's entries.
        for (candidate in hostAndParents(host)) {
            val jar = synchronized(store) { store[candidate] } ?: continue
            synchronized(jar) {
                for ((name, entry) in jar) {
                    if (entry.domain != null &&
                        (host == entry.domain || host.endsWith(".${entry.domain}"))
                    ) {
                        result.putIfAbsent(name, entry.value)
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

    private fun hostAndParents(host: String): List<String> {
        val parts = host.split('.')
        if (parts.size <= 1) return listOf(host)
        return (0 until parts.size - 1).map { i ->
            parts.subList(i, parts.size).joinToString(".")
        }
    }

    private class ParsedHeader(val domain: String?, val cookies: List<Pair<String, String>>)

    private fun parseCookieHeader(header: String): ParsedHeader {
        var domain: String? = null
        val cookies = mutableListOf<Pair<String, String>>()

        for (part in header.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.contains('=')) continue
            val name = trimmed.substringBefore('=').trim()
            val value = trimmed.substringAfter('=').trim()
            val lower = name.lowercase()

            if (lower == "domain") {
                domain = value.removePrefix(".").lowercase().ifEmpty { null }
            } else if (lower !in COOKIE_ATTRIBUTES) {
                cookies += name to value
            }
        }

        return ParsedHeader(domain, cookies)
    }

    private val COOKIE_ATTRIBUTES = setOf("path", "expires", "max-age", "samesite", "secure", "httponly")

    companion object {
        private val instance = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = instance
    }
}
