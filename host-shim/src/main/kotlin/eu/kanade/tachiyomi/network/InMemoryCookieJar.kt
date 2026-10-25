package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** In-memory cookie jar. Persistence is irrelevant for a single harness run. */
class InMemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val jar = store.computeIfAbsent(url.host) { mutableListOf() }
        synchronized(jar) {
            cookies.forEach { cookie ->
                jar.removeAll { it.name == cookie.name && it.path == cookie.path }
                jar.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()

        // A cookie set with Domain=.example.com is stored under the host that received the
        // Set-Cookie response (e.g. "example.com"), but Cookie.matches(url) returns true for
        // any subdomain (api.example.com, www.example.com). Walking every bucket is the only
        // way to find those cross-host matches - the same lookup a browser performs.
        for ((_, jar) in store) {
            synchronized(jar) {
                jar.removeAll { it.expiresAt < now }
                result.addAll(jar.filter { it.matches(url) })
            }
        }

        return result
    }
}
