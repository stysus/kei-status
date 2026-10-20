package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** In-memory cookie jar. Persistence is irrelevant for a single harness run. */
class InMemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val jar = store.getOrPut(url.host) { mutableListOf() }
        synchronized(jar) {
            cookies.forEach { cookie ->
                jar.removeAll { it.name == cookie.name && it.path == cookie.path }
                jar.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val jar = store[url.host] ?: return emptyList()
        synchronized(jar) {
            val now = System.currentTimeMillis()
            jar.removeAll { it.expiresAt < now }
            return jar.filter { it.matches(url) }
        }
    }
}
