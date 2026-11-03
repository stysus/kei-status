package kei.status.engine

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * The search scenario's miss handling. The 2026-11-02 sweep produced 380 search WARNs that were not
 * broken searches: sites serve the same title under several gallery ids, so the popular entry's URL
 * never appears in the results, and gallery engines read the punctuation a listing title kept as
 * search syntax. The scenario now also accepts the same title, and retries once without
 * punctuation, before calling it a miss.
 */
class SearchRetryTest {

    private val runner = HarnessRunner(
        HarnessConfig(
            sourceRoot = Path.of("extensions-source"),
            module = ":src:en:example",
            scratchRoot = Path.of("build/scratch"),
        ),
    )

    /** Answers each query with the page queued for it, and records what it was asked. */
    private class FakeSource : HttpSource() {
        override val baseUrl = "https://example.com"
        override val lang = "en"
        override val name = "Fake"
        override val supportsLatest = false

        val queries = mutableListOf<String>()
        val answers = mutableMapOf<String, MangasPage>()

        // A failure the retry-only path hits; thrown on every call for that query, so a 429
        // exhausts the throttle retries instead of clearing on the second attempt.
        var failOnQuery: Pair<String, Exception>? = null

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            queries += query
            failOnQuery?.let { (target, e) -> if (query == target) throw e }
            return answers[query] ?: MangasPage(emptyList(), false)
        }

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
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

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
        thumbnail_url = "https://img.example.com/t.jpg"
    }

    @Test
    fun `the same title under a different gallery id is a hit, not a miss`() = runBlocking {
        val source = FakeSource()
        val advertised = manga("/gallery/386132/", "[Maron ☆ Maron] Gaki ni Modotte Yarinaoshi!!! | Turning Back Into a Kid [English]")
        source.answers["Gaki ni Modotte Yarinaoshi!!!"] = MangasPage(
            listOf(manga("/gallery/293298/", "[Maron ☆ Maron] Gaki ni Modotte Yarinaoshi!!!")),
            false,
        )

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(Status.PASS, result.status)
        assertTrue(result.notes.none { it.startsWith("query \"") })
    }

    @Test
    fun `a query the engine reads as syntax is retried without punctuation`() = runBlocking {
        val source = FakeSource()
        val advertised = manga("/g/1/", "(C92) Lust Vampire (Fate/Grand Order)")
        source.answers["C92 Lust Vampire Fate Grand Order"] = MangasPage(
            listOf(manga("/g/1/", "(C92) Lust Vampire (Fate/Grand Order)")),
            false,
        )

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(listOf("(C92) Lust Vampire (Fate/Grand Order)", "C92 Lust Vampire Fate Grand Order"), source.queries)
        assertEquals(Status.PASS, result.status)
        assertTrue(result.notes.any { it.contains("the retry with") })
    }

    @Test
    fun `a retry that misses too stays a warn with both notes`() = runBlocking {
        val source = FakeSource()
        val advertised = manga("/gallery/1/", "Mojo!")

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(listOf("Mojo!", "Mojo"), source.queries)
        assertEquals(Status.WARN, result.status)
        assertTrue(result.notes.any { it == "query \"Mojo!\" matched no results in the first page" })
        assertTrue(result.notes.any { it.contains("also matched nothing") })
    }

    @Test
    fun `a clean query that misses is not retried`() = runBlocking {
        val source = FakeSource()
        val advertised = manga("/gallery/1/", "Ochako")

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(listOf("Ochako"), source.queries)
        assertEquals(Status.WARN, result.status)
    }

    @Test
    fun `a retry that throws is the retry's verdict, not a wider miss`() = runBlocking {
        // The first attempt returned an empty page; the retry failed. Reporting the miss would
        // claim the retry "matched nothing" when it never came back, and swallow the failure.
        // 503 is classified DOWN, like every standard 5xx.
        val source = FakeSource()
        val advertised = manga("/gallery/1/", "Mojo!")
        source.failOnQuery = "Mojo" to HttpException(503)

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(Status.DOWN, result.status)
        assertEquals(listOf("Mojo!", "Mojo"), source.queries)
        assertTrue(result.notes.none { it.contains("also matched nothing") })
        assertTrue(result.notes.any { it.contains("returned an empty list") })
    }

    @Test
    fun `a retry that is throttled is the retry's verdict too`() = runBlocking {
        val source = FakeSource()
        val advertised = manga("/gallery/1/", "Mojo!")
        source.failOnQuery = "Mojo" to HttpException(429, retryAfterSeconds = 0)

        val result = runner.searchScenario(source, advertised, host = null)

        assertEquals(Status.THROTTLED, result.status)
        assertTrue(result.notes.none { it.contains("also matched nothing") })
    }
}
