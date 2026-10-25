package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryCookieJarTest {

    private val jar = InMemoryCookieJar()

    @Test
    fun `cookies saved for a host are loaded back for the same host`() {
        val url = "https://example.com/path".toHttpUrl()
        val cookie = Cookie.Builder().name("a").value("1").domain("example.com").build()

        jar.saveFromResponse(url, listOf(cookie))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("a", loaded[0].name)
    }

    @Test
    fun `a root-domain cookie is sent to a subdomain request`() {
        // A Set-Cookie from example.com with Domain=.example.com (OkHttp stores it with
        // domain="example.com" and hostOnly=false) must be returned for api.example.com.
        val rootUrl = "https://example.com/".toHttpUrl()
        val cookie = Cookie.Builder().name("sid").value("x").domain("example.com").build()

        jar.saveFromResponse(rootUrl, listOf(cookie))

        val subUrl = "https://api.example.com/v1".toHttpUrl()
        val loaded = jar.loadForRequest(subUrl)
        assertEquals("subdomain must see the root cookie", 1, loaded.size)
        assertEquals("sid", loaded[0].name)
    }

    @Test
    fun `a subdomain cookie is not sent to a sibling subdomain`() {
        // A cookie with domain="api.example.com" (hostOnly) must not leak to www.example.com.
        val apiUrl = "https://api.example.com/".toHttpUrl()
        // hostOnly cookie: set without Domain attribute, so OkHttp marks hostOnly=true
        val cookie = Cookie.parse(apiUrl, "tok=abc; Path=/")!!

        jar.saveFromResponse(apiUrl, listOf(cookie))

        val wwwUrl = "https://www.example.com/".toHttpUrl()
        assertTrue(
            "sibling subdomain must not see a host-only cookie",
            jar.loadForRequest(wwwUrl).isEmpty(),
        )
    }

    @Test
    fun `a domain cookie set on a subdomain reaches the parent`() {
        // Set-Cookie from api.example.com with Domain=example.com; matches example.com too.
        val apiUrl = "https://api.example.com/".toHttpUrl()
        val cookie = Cookie.Builder().name("wide").value("y").domain("example.com").build()

        jar.saveFromResponse(apiUrl, listOf(cookie))

        val rootUrl = "https://example.com/".toHttpUrl()
        val loaded = jar.loadForRequest(rootUrl)
        assertEquals(1, loaded.size)
        assertEquals("wide", loaded[0].name)
    }

    @Test
    fun `expired cookies are not returned`() {
        val url = "https://example.com/".toHttpUrl()
        val expired = Cookie.Builder()
            .name("old").value("v").domain("example.com")
            .expiresAt(System.currentTimeMillis() - 1000)
            .build()

        jar.saveFromResponse(url, listOf(expired))

        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `duplicate cookies are replaced by name and path`() {
        val url = "https://example.com/".toHttpUrl()
        val first = Cookie.Builder().name("a").value("1").domain("example.com").build()
        val second = Cookie.Builder().name("a").value("2").domain("example.com").build()

        jar.saveFromResponse(url, listOf(first))
        jar.saveFromResponse(url, listOf(second))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("2", loaded[0].value)
    }
}
