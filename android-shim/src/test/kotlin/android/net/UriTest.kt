package android.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins how [Uri.encode] escapes and how the builder joins parts, because sources build real
 * requests from both: an escaping set that differs from Android's would silently rewrite a URL
 * instead of failing loudly.
 */
class UriTest {

    @Test
    fun `encode leaves the unreserved set alone`() {
        assertEquals("aZ09_-!.~'()*", Uri.encode("aZ09_-!.~'()*"))
    }

    @Test
    fun `encode escapes a space as percent-20 and not as a plus`() {
        assertEquals("a%20b", Uri.encode("a b"))
    }

    @Test
    fun `encode escapes a reserved character`() {
        assertEquals("%2F", Uri.encode("/"))
        assertEquals("%3F%23%26%3D", Uri.encode("?#&="))
    }

    @Test
    fun `encode writes a non-ASCII code point as UTF-8 octets`() {
        assertEquals("%C3%A9", Uri.encode("é"))
        assertEquals("%E2%9C%93", Uri.encode("✓"))
        assertEquals("%F0%9F%98%80", Uri.encode("\uD83D\uDE00"))
    }

    @Test
    fun `buildUpon appends a path segment and a query parameter`() {
        val built = Uri.parse("https://example.org/api/v1?page=1")
            .buildUpon()
            .appendPath("manga")
            .appendQueryParameter("q", "blue lock")
            .build()

        assertEquals("https://example.org/api/v1/manga?page=1&q=blue%20lock", built.toString())
    }

    @Test
    fun `buildUpon keeps the port and the fragment`() {
        val built = Uri.parse("http://example.org:8080/a/b#top")
            .buildUpon()
            .appendPath("c")
            .build()

        assertEquals("http://example.org:8080/a/b/c#top", built.toString())
    }

    @Test
    fun `buildUpon escapes an appended segment so it cannot add a separator`() {
        val built = Uri.parse("https://example.org")
            .buildUpon()
            .appendPath("a b/c")
            .build()

        assertEquals("https://example.org/a%20b%2Fc", built.toString())
    }

    @Test
    fun `buildUpon on a relative reference keeps the whole path`() {
        // The first slash of a relative reference is not a path boundary; only what follows the
        // authority of an absolute URI is.
        val built = Uri.parse("api/v1?page=1")
            .buildUpon()
            .appendPath("manga")
            .build()

        assertEquals("api/v1/manga?page=1", built.toString())
    }
}
