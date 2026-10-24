package kei.status.engine

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveImageUrlTest {

    /**
     * The reader resolves a page's image URL through [HttpSource.getImageUrl] before it builds the
     * image request, and several extensions ship page lists that lean on exactly that: Dragon Ball
     * Multiverse, Akuma and NovelCool fill only `page.url` and resolve the image in `getImageUrl`.
     */
    private class FakeSource : HttpSource() {
        override val baseUrl = "https://example.com"
        override val lang = "en"
        override val name = "Fake"
        override val supportsLatest = false
        var resolvedPages = 0

        override suspend fun getImageUrl(page: Page): String {
            resolvedPages += 1
            return "https://img.example.com/resolved-${page.index}"
        }

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
        override fun getMangaUrl(manga: SManga) = baseUrl
        override fun getChapterUrl(chapter: SChapter) = baseUrl
    }

    @Test
    fun `a page with no image url is resolved the way the reader resolves it`() = runBlocking {
        val source = FakeSource()
        val page = Page(0, url = "/2")

        source.resolveImageUrl(page)

        assertEquals("https://img.example.com/resolved-0", page.imageUrl)
        assertEquals(1, source.resolvedPages)
    }

    @Test
    fun `a blank image url is resolved too, not only a missing one`() = runBlocking {
        // jsoup's `attr` returns "" for a missing attribute, and the reader checks
        // `isNullOrEmpty` - a blank string must reach getImageUrl, not crash the request builder.
        val source = FakeSource()
        val page = Page(0, imageUrl = "")

        source.resolveImageUrl(page)

        assertEquals("https://img.example.com/resolved-0", page.imageUrl)
        assertEquals(1, source.resolvedPages)
    }

    @Test
    fun `a page that already carries an image url is left alone`() = runBlocking {
        val source = FakeSource()
        val page = Page(0, imageUrl = "https://img.example.com/direct")

        source.resolveImageUrl(page)

        assertEquals("https://img.example.com/direct", page.imageUrl)
        assertEquals(0, source.resolvedPages)
    }
}
