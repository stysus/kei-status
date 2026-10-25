package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** In-memory cookie jar. Persistence is irrelevant for a single harness run. */
class InMemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            val jar = store.computeIfAbsent(cookie.domain) { mutableListOf() }
            synchronized(jar) {
                jar.removeAll { it.name == cookie.name && it.path == cookie.path }
                jar.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()

        // A cookie's effective domain is its bucket key, so the only buckets that can
        // contribute are the request host itself and every parent domain.  For a request
        // to "api.cdn.example.com" this checks at most three buckets instead of the
        // entire store, while Cookie.matches(url) remains the final guard.
        for (candidate in hostAndParents(url.host)) {
            val jar = store[candidate] ?: continue
            synchronized(jar) {
                jar.removeAll { it.expiresAt < now }
                result.addAll(jar.filter { it.matches(url) })
            }
        }

        return result
    }

    /**
     * The host itself and its parent domains, stopping before the TLD.
     *
     * "api.cdn.example.com" → ["api.cdn.example.com", "cdn.example.com", "example.com"]
     *
     * Over-inclusion is harmless: [Cookie.matches] is the final filter, and a candidate
     * whose bucket does not exist is skipped by the null check.
     */
    private fun hostAndParents(host: String): List<String> {
        val parts = host.split('.')
        if (parts.size <= 1) return listOf(host)
        return (0 until parts.size - 1).map { i ->
            parts.subList(i, parts.size).joinToString(".")
        }
    }
}
