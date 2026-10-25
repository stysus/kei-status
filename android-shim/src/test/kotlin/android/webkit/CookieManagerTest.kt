package android.webkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CookieManagerTest {

    private lateinit var cm: CookieManager

    @Before
    fun setUp() {
        cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
    }

    @Test
    fun `cookies saved for a host are loaded back for the same host`() {
        cm.setCookie("https://example.com/", "a=1")

        val result = cm.getCookie("https://example.com/")
        assertEquals("a=1", result)
    }

    @Test
    fun `a root-domain cookie is sent to a subdomain request`() {
        cm.setCookie("https://example.com/", "sid=x; Domain=example.com; Path=/")

        val result = cm.getCookie("https://api.example.com/v1")
        assertTrue("subdomain must see the root cookie, got: $result", result?.contains("sid=x") == true)
    }

    @Test
    fun `a cookie without Domain attribute is host-only`() {
        // No Domain= attribute means host-only: only the exact host sees it.
        cm.setCookie("https://api.example.com/", "tok=abc; Path=/")

        assertNull(
            "sibling subdomain must not see a host-only cookie",
            cm.getCookie("https://www.example.com/"),
        )
        assertNull(
            "parent domain must not see a host-only cookie",
            cm.getCookie("https://example.com/"),
        )
        assertTrue(
            "same host must see its own cookie",
            cm.getCookie("https://api.example.com/")?.contains("tok=abc") == true,
        )
    }

    @Test
    fun `a domain cookie set on a subdomain reaches the parent`() {
        cm.setCookie("https://api.example.com/", "wide=y; Domain=example.com; Path=/")

        val result = cm.getCookie("https://example.com/")
        assertTrue("parent domain must see the cookie, got: $result", result?.contains("wide=y") == true)
    }

    @Test
    fun `a domain cookie set on a subdomain reaches a sibling`() {
        cm.setCookie("https://api.example.com/", "shared=z; Domain=example.com; Path=/")

        val result = cm.getCookie("https://www.example.com/")
        assertTrue("sibling subdomain must see the cookie, got: $result", result?.contains("shared=z") == true)
    }

    @Test
    fun `empty value removes the cookie`() {
        cm.setCookie("https://example.com/", "a=1")
        cm.setCookie("https://example.com/", "a=")

        assertNull(cm.getCookie("https://example.com/"))
    }

    @Test
    fun `removeAllCookies clears everything`() {
        cm.setCookie("https://example.com/", "a=1")
        cm.setCookie("https://other.com/", "b=2")
        cm.removeAllCookies(null)

        assertNull(cm.getCookie("https://example.com/"))
        assertNull(cm.getCookie("https://other.com/"))
    }
}
