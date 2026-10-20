package kei.status.engine

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.nio.file.Path
import java.time.Instant
import java.time.Duration as JavaDuration

data class HarnessConfig(
    val sourceRoot: Path,
    val module: String,
    val scratchRoot: Path,
    val timeout: JavaDuration = JavaDuration.ofSeconds(45),
    val mangaLimit: Int = 3,
    val pageLimit: Int = 3,
    val probeImages: Boolean = true,
    /** Restricts a multi-source module (e.g. MangaDex's ~60 languages) to these languages. */
    val langs: Set<String> = emptySet(),
)

class HarnessRunner(private val config: HarnessConfig) {

    fun run(): RunReport = runBlocking {
        val layout = ModuleLayouts.read(config.sourceRoot, config.module)
        HarnessBootstrap.install(config.scratchRoot)

        val reports = SourceLoader(config.sourceRoot, layout).use { loader ->
            val sources = try {
                loader.createSources().filter { config.langs.isEmpty() || it.lang in config.langs }
            } catch (e: Throwable) {
                // A missing host class must not abort the run: report it and stop here.
                return@runBlocking buildReport(listOf(loadFailureReport(e)))
            }

            if (sources.isEmpty() && config.langs.isNotEmpty()) {
                return@runBlocking buildReport(
                    listOf(
                        loadFailureReport(
                            IllegalArgumentException("no source matched --lang ${config.langs.joinToString()}"),
                        ),
                    ),
                )
            }

            sources.map { runGuarded(it) }
        }

        buildReport(reports)
    }

    /** Keeps one source's unexpected failure from discarding the results of the others. */
    private suspend fun runGuarded(source: CatalogueSource): SourceReport = try {
        withContext(Dispatchers.IO) { runSource(source) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        loadFailureReport(e)
    }

    private fun buildReport(sources: List<SourceReport>) = RunReport(
        generatedAt = Instant.now().toString(),
        module = config.module,
        sourceRoot = config.sourceRoot.toAbsolutePath().toString(),
        extensionCommit = gitCommit(config.sourceRoot),
        sources = sources,
    )

    private fun loadFailureReport(e: Throwable): SourceReport {
        if (TRACE) e.printStackTrace()
        val diagnosis = Checks.diagnose(e)
        return SourceReport(
            sourceId = 0,
            name = config.module,
            lang = "",
            baseUrl = "",
            status = diagnosis.status,
            scenarios = listOf(
                ScenarioResult(
                    name = "load",
                    status = diagnosis.status,
                    durationMs = 0,
                    message = diagnosis.message,
                    notes = diagnosis.chain,
                ),
            ),
        )
    }

    private suspend fun runSource(source: CatalogueSource): SourceReport {
        val scenarios = mutableListOf<ScenarioResult>()

        scenarios += capture("metadata") { Checks.metadata(source.id, source.name, source.lang, baseUrlOf(source)) }.result

        scenarios += capture("connectivity") { probeHome(source) }.result

        val popular = capture("popular", ::mangaNotes, ::mangaSamples) { source.getPopularManga(1) }
        scenarios += popular.result

        val latest = if (source.supportsLatest) {
            capture("latest", ::mangaNotes, ::mangaSamples) { source.getLatestUpdates(1) }
        } else {
            null
        }
        scenarios += latest?.result ?: ScenarioResult("latest", Status.SKIP, 0, "supportsLatest = false")

        scenarios += capture("filters", samples = { listOf("${it.size} filters") }) { source.getFilterList() }.result

        // Some sources have no content for every language they advertise (MangaDex has no
        // "popular in Japanese" because the original language is not a translated language), so
        // fall back to latest before giving up on the deeper scenarios.
        val firstManga = popular.value?.mangas?.firstOrNull()
            ?: latest?.value?.mangas?.firstOrNull()
        if (firstManga == null) {
            scenarios += ScenarioResult("chapters", Status.SKIP, 0, "no manga available from popular or latest")
            scenarios += ScenarioResult("pages", Status.SKIP, 0, "no manga available from popular or latest")
        } else {
            val update = capture("chapters", ::chapterNotes, ::chapterSamples) {
                source.getMangaUpdate(firstManga, emptyList(), fetchDetails = true, fetchChapters = true)
            }
            scenarios += update.result

            val firstChapter = update.value?.chapters?.firstOrNull()
            if (firstChapter == null) {
                scenarios += ScenarioResult("pages", Status.SKIP, 0, "no chapters available")
            } else {
                val pages = capture("pages", ::pageNotes, ::pageSamples) { source.getPageList(firstChapter) }
                scenarios += pages.result

                if (config.probeImages && pages.value != null) {
                    scenarios += capture("images") { probeImages(source, pages.value) }.result
                }
            }
        }

        return SourceReport(
            sourceId = source.id,
            name = source.name,
            lang = source.lang,
            baseUrl = baseUrlOf(source),
            status = worst(scenarios),
            scenarios = scenarios,
        )
    }

    private fun baseUrlOf(source: CatalogueSource): String =
        (source as? HttpSource)?.baseUrl ?: ""

    private suspend fun probeHome(source: CatalogueSource): List<String> {
        val httpSource = source as? HttpSource
            ?: return listOf("not an HttpSource; connectivity probe skipped")

        val url = httpSource.getHomeUrl()
        val request = Request.Builder().url(url).headers(httpSource.headers).build()

        httpSource.client.newCall(request).await().use { response ->
            val notes = mutableListOf<String>()
            if (!response.isSuccessful) notes += "home page returned HTTP ${response.code}"
            if (response.body.contentLength() == 0L) notes += "home page body is empty"
            if (response.header("cf-mitigated") != null) notes += "challenge header present"
            return notes
        }
    }

    /**
     * Downloads each page image through the source's own image request and decodes it.
     *
     * Going through `getImageRequest` is the only faithful way: sources may rewrite the URL there
     * (MangaDex asks an At-Home endpoint and keeps a relative path in `imageUrl`), and several
     * descramble the bytes in an interceptor. The full body is fetched - not a byte range - because
     * a partial response cannot be decoded, and decoding is what proves the descrambler produced a
     * real image rather than corrupt output.
     */
    private suspend fun probeImages(source: CatalogueSource, pages: List<Page>): List<String> {
        val httpSource = source as? HttpSource ?: return emptyList()
        val notes = mutableListOf<String>()

        pages.take(config.pageLimit).forEachIndexed { index, page ->
            val request = try {
                httpSource.getImageRequest(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "page[$index] image request could not be built: ${e.message}"
                return@forEachIndexed
            }

            try {
                httpSource.client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        notes += "page[$index] image returned HTTP ${response.code}"
                        return@use
                    }
                    val type = response.header("Content-Type").orEmpty()
                    if (!type.startsWith("image/")) notes += "page[$index] content-type is '$type'"

                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) {
                        notes += "page[$index] image body is empty"
                        return@use
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        notes += "page[$index] image bytes did not decode (${bytes.size} bytes, '$type')"
                    } else {
                        bitmap.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "page[$index] image request failed: ${e.message}"
            }
        }

        return notes
    }

    private fun mangaNotes(page: MangasPage) = Checks.mangaPage(page)

    private fun chapterNotes(update: SMangaUpdate) = Checks.chapters(update.chapters)

    private fun pageNotes(pages: List<Page>) = Checks.pages(pages)

    private fun mangaSamples(page: MangasPage) = page.mangas.take(config.mangaLimit)
        .map { "${it.title} -> ${it.url}" }

    private fun chapterSamples(update: SMangaUpdate) = update.chapters.take(config.mangaLimit)
        .map { "${it.name} -> ${it.url}" }

    private fun pageSamples(pages: List<Page>) = pages.take(config.pageLimit)
        .map { it.imageUrl ?: it.url }

    private suspend fun <T> capture(
        name: String,
        notes: (T) -> List<String> = { emptyList() },
        samples: (T) -> List<String> = { emptyList() },
        block: suspend () -> T,
    ): Captured<T> {
        val start = System.nanoTime()
        return try {
            val value = withTimeout(config.timeout.toMillis()) { block() }
            val findings = notes(value)
            Captured(
                value = value,
                result = ScenarioResult(
                    name = name,
                    status = if (findings.isEmpty()) Status.PASS else Status.WARN,
                    durationMs = elapsedMs(start),
                    notes = findings,
                    samples = samples(value),
                ),
            )
        } catch (e: CancellationException) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Captured(
                null,
                ScenarioResult(name, Status.FAIL, elapsedMs(start), "timed out after ${config.timeout.seconds}s"),
            )
        } catch (e: Throwable) {
            if (TRACE) e.printStackTrace()
            val diagnosis = Checks.diagnose(e)
            Captured(
                null,
                ScenarioResult(
                    name = name,
                    status = diagnosis.status,
                    durationMs = elapsedMs(start),
                    message = diagnosis.message,
                    notes = diagnosis.chain,
                ),
            )
        }
    }

    private fun worst(scenarios: List<ScenarioResult>): Status =
        scenarios.maxByOrNull { it.status.severity }?.status ?: Status.SKIP

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private fun gitCommit(root: Path): String? = runCatching {
        ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader()
            .readText()
            .trim()
            .ifBlank { null }
    }.getOrNull()
}

class Captured<T>(val value: T?, val result: ScenarioResult)

/** Set KEI_STATUS_TRACE=1 to print stack traces for every classified failure. */
private val TRACE = System.getenv("KEI_STATUS_TRACE") != null
