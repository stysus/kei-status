package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The query the search scenario sends, derived from a source's decorated listing title.
 *
 * The first case is the one the 2026-10-31 sweep exposed: HentaiEnvy advertises the decorated
 * string, its search returns nothing for it, and the plain title on the same site matches.
 */
class SearchQueryTest {

    @Test
    fun `an author circle, subtitle and tag tail are stripped to the manga title`() {
        val advertised = "[Mokugyuutan. (Suzumiya)] Futanari Musume no Dekashiri Mama NTR Nikki - " +
            "El diario de NTR de una Futanari y su voluptuosa Madre [Spanish] [Thrommer]"

        assertEquals(
            "Futanari Musume no Dekashiri Mama NTR Nikki",
            searchQuery(advertised),
        )
    }

    @Test
    fun `an alternate title after a pipe is dropped`() {
        assertEquals("Yixuan", searchQuery("Yixuan | Yixuan 1-2"))
    }

    @Test
    fun `an undecorated title is sent as advertised`() {
        assertEquals("K-ON! Shuffle", searchQuery("K-ON! Shuffle"))
    }

    @Test
    fun `a title that is all decoration keeps its advertised form`() {
        assertEquals("[Anthology]", searchQuery("[Anthology]"))
    }

    @Test
    fun `the leftover spacing is collapsed`() {
        assertEquals("Title", searchQuery("  [Group]  Title  [Tag] "))
    }
}