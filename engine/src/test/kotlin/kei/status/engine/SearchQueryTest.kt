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

    @Test
    fun `plainQuery keeps only what a site's own engine matches on`() {
        // The shapes the 2026-11-02 sweep exposed: a gallery engine treats `!` as exclusion and
        // parentheses as grouping, so "Mojo!" and "(C92) Lust Vampire (Fate/Grand Order)" matched
        // nothing, and a CJK bracketed tail survives searchQuery.
        assertEquals("Mojo", plainQuery("Mojo!"))
        assertEquals("C92 Lust Vampire Fate Grand Order", plainQuery("(C92) Lust Vampire (Fate/Grand Order)"))
        assertEquals("I m the worst Zenjitu Tan", plainQuery("「I'm the worst...(Zenjitu Tan)"))
        assertEquals("Master Piece", plainQuery("Master_ Piece"))
        assertEquals("Ochako", plainQuery("Ochako"))
        assertEquals("", plainQuery("!!!"))
    }
}
