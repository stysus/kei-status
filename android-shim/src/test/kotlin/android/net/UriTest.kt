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

    @Test
    fun `getPath strips query and fragment`() {
        val uri = Uri.parse("https://example.com/manga/12345?ref=home#ch1")

        assertEquals("/manga/12345", uri.getPath())
        assertEquals("/manga/12345", uri.getEncodedPath())
    }

    @Test
    fun `getPath on a relative reference keeps the whole path`() {
        // Same rule as buildUpon: the first slash of a relative reference is not a boundary, and a
        // single segment with no slash is still a path.
        assertEquals("api/v1", Uri.parse("api/v1?page=1").getPath())
        assertEquals("api", Uri.parse("api").getPath())
    }

    @Test
    fun `getEncodedQuery strips the fragment`() {
        val uri = Uri.parse("https://example.com/path?q=1&page=2#top")

        assertEquals("q=1&page=2", uri.getEncodedQuery())
        assertEquals("q=1&page=2", uri.getQuery())
    }

    @Test
    fun `getEncodedQuery returns null when there is no query`() {
        assertEquals(null, Uri.parse("https://example.com/path#frag").getEncodedQuery())
        assertEquals(null, Uri.parse("https://example.com/path").getEncodedQuery())
    }

    @Test
    fun `getAuthority strips query and fragment when no path is present`() {
        // A URI like "https://example.com?q=1" has no path; the authority must not leak the query.
        assertEquals("example.com", Uri.parse("https://example.com?q=1").getAuthority())
        assertEquals("example.com", Uri.parse("https://example.com#frag").getAuthority())
        assertEquals("example.com:8080", Uri.parse("https://example.com:8080?q=1").getAuthority())
    }

    @Test
    fun `getHost strips query and fragment when no path is present`() {
        assertEquals("example.com", Uri.parse("https://example.com?q=1").getHost())
        assertEquals("example.com", Uri.parse("https://example.com#frag").getHost())
    }
}
