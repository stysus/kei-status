package kei.status.engine

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kei.status.webkit.Chrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeoutException
class Captured<T>(val value: T?, val result: ScenarioResult)

class HarnessRunner(private val config: HarnessConfig) {

    companion object {
        /**
         * A module-level failure the runner itself could not survive.
         *
         * Without this, the caller's only option is to skip the module — it vanishes from the
         * results, the summary count shrinks silently, and the store keeps advertising whatever
         * the last successful sweep said.  Building a report instead makes the crash visible in
         * the same format everything else uses.
         *
         * Scenario name `"run"` distinguishes this from the engine's own `"load"` failures.
         */
        fun crashReport(config: HarnessConfig, e: Throwable): RunReport {
            if (TRACE) e.printStackTrace()
            val diagnosis = Checks.diagnose(e)
            return RunReport(
                generatedAt = Instant.now().toString(),
                module = config.module,
                sourceRoot = "",
                extensionCommit = null,
                sources = listOf(
                    SourceReport(
                        sourceId = 0,
                        name = config.module,
                        lang = "",
                        baseUrl = "",
                        status = diagnosis.status,
                        scenarios = listOf(
                            ScenarioResult("run", diagnosis.status, 0, diagnosis.message, diagnosis.chain),
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * One entry per (extension class, site). A module that declares one source per language runs
     * the same class against the same site once per language; core fetches filter data in the
     * background and caches it per source id, so without this every variant would download the
     * same filter page.
     */
    private val filterSites = mutableMapOf<String, SiteResult>()

    /**
     * One connectivity probe per extension class and site, for the same reason as [filterSites].
     *
     * A module can declare one source per language against a single base URL - HentaiHand has 34,
     * MangaDot 108 - and whether the home page answers is a property of the site, not of the
     * variant. Probing it 34 times spent 255s of HentaiHand's 672s arriving at one answer.
     */
    private val homeSites = mutableMapOf<String, SiteResult>()

    /**
     * Hosts that answered HTTP 429 during this run, so the rest of the sources on them are not asked
     * again.
     *
     * A module can declare the same site once per language - MangaDot declares 108 sources, all on
     * `mangadot.net` - and the sweep walks them one after another. Once the site starts throttling,
     * every later source gets a 429 that says nothing about the extension and costs another request
     * that keeps the throttle alive. One trip per host turns dozens of identical failures into one
     * finding and a skip for the rest, without asking again.
     *
     * Scoped to this runner, which is one module: a shard checks many modules in one JVM, and a
     * throttle on one site must not silence a different one that happens to share a host.
     */
    private val throttledHosts = mutableSetOf<String>()

    fun run(): RunReport = runBlocking {
        try {
            runModule()
        } finally {
            // Whatever WebViews this module's extensions opened and never destroyed are
            // retired here: one Chromium serves the whole sweep, so leaked tabs and their
            // cookie pollers would otherwise accumulate across every module that follows.
            runCatching { Chrome.closeAllPages() }
        }
    }

    private suspend fun runModule(): RunReport {
        // A module that was never compiled has no layout file. Report that as a failed load instead
        // of throwing: a CI run over many modules must still publish what the other modules said.
        val layout = try {
            ModuleLayouts.read(config.sourceRoot, config.module)
        } catch (e: Throwable) {
            return buildReport(listOf(loadFailureReport(e)))
        }
        HarnessBootstrap.install(config.scratchRoot)

        val loader = SourceLoader(config.sourceRoot, layout)
        // Loading runs extension code too - constructors and static initialisers - so it gets the
        // same deadline as a scenario rather than being the one place that can hang forever.
        val sources = when (val loaded = withDeadline(config.timeout) { loader.createSources() }) {
            is DeadlineOutcome.Value ->
                loaded.value.filter { config.langs.isEmpty() || it.lang in config.langs }

            is DeadlineOutcome.Failed ->
                // A missing host class must not abort the run: report it and stop here.
                return buildReport(listOf(loadFailureReport(loaded.error)))

            DeadlineOutcome.Expired -> return buildReport(
                listOf(
                    loadFailureReport(
                        TimeoutException("loading the extension timed out after ${config.timeout.seconds}s"),
                    ),
                ),
            )
        }

        if (sources.isEmpty() && config.langs.isNotEmpty()) {
            return buildReport(
                listOf(
                    loadFailureReport(
                        IllegalArgumentException("no source matched --lang ${config.langs.joinToString()}"),
                    ),
                ),
            )
        }

        val reports = sources.map { runGuarded(it) }

        return buildReport(reports)
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
        module = canonicalModule(config.module, config.sourceRoot),
        sourceRoot = config.sourceRoot.toAbsolutePath().toString(),
        extensionCommit = gitCommit(config.sourceRoot),
        sources = sources,
        throttledHosts = throttledHosts.sorted(),
    )

    /**
     * Reports a module as a Gradle path even when it was given as a directory.
     *
     * `--module` accepts either form, and the directory form is convenient locally but would put an
     * absolute path in the report and on the published page.
     */
    private fun canonicalModule(module: String, sourceRoot: Path): String {
        if (module.startsWith(":")) return module
        val root = runCatching { sourceRoot.toAbsolutePath().normalize() }.getOrNull() ?: return module
        val dir = runCatching { Path.of(module).toAbsolutePath().normalize() }.getOrNull() ?: return module
        if (!dir.startsWith(root)) return module
        return ":" + root.relativize(dir).toString().replace(java.io.File.separatorChar, ':')
    }

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
        val baseUrl = baseUrlOf(source)

        // A source that talks to a server the reader hosts themselves has nothing public to check.
        // Komga ships no address at all until one is typed into its settings; the others default
        // to loopback. Probing either only reports "connection refused", which is a verdict on the
        // missing server, not on the extension.
        val unconfigured = baseUrl.isBlank() && source is ConfigurableSource
        if (unconfigured || Checks.selfHosted(baseUrl)) {
            val reason = if (unconfigured) {
                "the address is set in the source's own settings; it runs against a server you host yourself"
            } else {
                "the source runs against a server you host yourself, so there is nothing public to check"
            }
            return SourceReport(
                sourceId = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = baseUrl,
                status = Status.SKIP,
                scenarios = listOf(ScenarioResult("self-hosted", Status.SKIP, 0, reason)),
            )
        }

        val scenarios = mutableListOf<ScenarioResult>()

        // The host, not the base URL: a site reached at both `example.com` and `www.example.com` is
        // one server with one budget. Null when there is nothing to key on, so a source with no
        // address neither trips the breaker nor gets skipped by it.
        val host: String? = (baseUrl.toHttpUrlOrNull()?.host ?: baseUrl).takeIf { it.isNotBlank() }
        if (host != null && host in throttledHosts) {
            return SourceReport(
                sourceId = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = baseUrl,
                status = Status.SKIP,
                scenarios = listOf(
                    ScenarioResult(
                        "throttled",
                        Status.SKIP,
                        0,
                        "$host answered HTTP 429 earlier in this run; this source was not checked",
                    ),
                ),
            )
        }

        scenarios += capture("metadata", notes = { it }) { Checks.metadata(source.id, source.name, source.lang, baseUrlOf(source)) }.result

        scenarios += connectivityScenario(source, host)

        val popular = capture("popular", ::mangaNotes, ::mangaSamples, host = host) { source.getPopularManga(1) }
        scenarios += popular.result

        val latest = if (source.supportsLatest) {
            capture("latest", ::mangaNotes, ::mangaSamples, host = host) { source.getLatestUpdates(1) }
        } else {
            null
        }
        scenarios += latest?.result ?: ScenarioResult("latest", Status.SKIP, 0, "supportsLatest = false")

        scenarios += filtersScenario(source, host)

        // Some sources have no content for every language they advertise (MangaDex has no
        // "popular in Japanese" because the original language is not a translated language), so
        // fall back to latest before giving up on the deeper scenarios.
        val firstManga = popular.value?.mangas?.firstOrNull()
            ?: latest?.value?.mangas?.firstOrNull()
        if (firstManga == null) {
            scenarios += ScenarioResult("chapters", Status.SKIP, 0, "no manga available from popular or latest")
            scenarios += ScenarioResult("pages", Status.SKIP, 0, "no manga available from popular or latest")
        } else {
            val update = capture("chapters", ::chapterNotes, ::chapterSamples, host = host) {
                source.getMangaUpdate(firstManga, emptyList(), fetchDetails = true, fetchChapters = true)
            }
            scenarios += update.result

            val chapter = chapterToProbe(update.value?.chapters.orEmpty())
            if (chapter == null) {
                scenarios += ScenarioResult("pages", Status.SKIP, 0, "no chapters available")
            } else {
                val pages = capture("pages", ::pageNotes, ::pageSamples, host = host) { source.getPageList(chapter) }
                scenarios += if (Checks.lockedChapter(pages.result.message)) {
                    // The chapter list carries no lock flag, so a premium chapter is only visible
                    // once the source refuses to serve it. That refusal says the content needs an
                    // account, not that the extension is broken, and probing further chapters to
                    // find a free one would multiply the page loads a sweep already makes.
                    pages.result.copy(
                        status = Status.SKIP,
                        notes = pages.result.notes + "pages are only checked on a chapter that needs no unlock",
                    )
                } else {
                    pages.result
                }

                if (config.probeImages && pages.value != null) {
                    scenarios += capture("images", notes = { it }, host = host) { probeImages(source, pages.value) }.result
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

    /**
     * Runs the filter-list scenario once per extension class and site.
     *
     * `getFilterList()` is documented as fast and I/O-free, but core also kicks off a background
     * fetch of the site's filter page the first time it is called for a source. A module that
     * declares one source per language would therefore download the same page once per language -
     * and fail the same way, since the fetch is a property of the site, not of the variant. The
     * first source's result is reported for the rest, with a note saying so.
     */
    private suspend fun filtersScenario(source: CatalogueSource, host: String?): ScenarioResult =
        sharedPerSite(source, filterSites) {
            capture("filters", samples = { listOf("${it.size} filters") }, host = host) { source.getFilterList() }.result
        }

    /** The home-page probe, once per site, for the same reason. */
    private suspend fun connectivityScenario(source: CatalogueSource, host: String?): ScenarioResult =
        sharedPerSite(source, homeSites) {
            capture("connectivity", notes = { it }, host = host) { probeHome(source) }.result
        }

    /**
     * Reports [scenario]'s result for the first source on a site and replays it for the rest.
     *
     * The verdict is copied, not recomputed: the extra note explains why the duration is zero, and
     * a scenario that passed for the site passes for each variant of it. Recomputing would turn
     * every replayed `PASS` into a `WARN` on the strength of a note.
     */
    private suspend fun sharedPerSite(
        source: CatalogueSource,
        cache: MutableMap<String, SiteResult>,
        scenario: suspend () -> ScenarioResult,
    ): ScenarioResult {
        val site = cache.getOrPut("${sourceType(source).name}|${baseUrlOf(source)}") { SiteResult() }

        site.result?.let { leader ->
            return leader.copy(
                durationMs = 0,
                notes = leader.notes + "same site as ${site.leader}; not checked again",
            )
        }

        site.leader = "${source.name} [${source.lang}]"
        return scenario().also { site.result = it }
    }

    /**
     * The extension's own class, not the anonymous per-language subclass the generated factory
     * creates for each `source {}` block.
     */
    private fun sourceType(source: CatalogueSource): Class<*> {
        var type: Class<*> = source.javaClass
        while (type.isAnonymousClass && type.superclass != null) type = type.superclass
        return type
    }

    private class SiteResult {
        var leader: String = ""
        var result: ScenarioResult? = null
    }

    private suspend fun probeHome(source: CatalogueSource): List<String> {
        val httpSource = source as? HttpSource
            ?: return listOf("not an HttpSource; connectivity probe skipped")

        val url = httpSource.getHomeUrl()
        val request = Request.Builder().url(url).headers(httpSource.headers).build()

        httpSource.client.newCall(request).await().use { response ->
            val notes = mutableListOf<String>()
            if (!response.isSuccessful) notes += "home page returned HTTP ${response.code}"
            // contentLength() is -1 when the body is chunked, so an empty chunked response
            // slips past a == 0L check.  A one-byte peek answers "did anything arrive" for
            // the unknown case without downloading the page.
            val empty = when (val length = response.body.contentLength()) {
                0L -> true
                in 1..Long.MAX_VALUE -> false
                else -> runCatching { response.peekBody(1).string().isEmpty() }.getOrDefault(false)
            }
            if (empty) notes += "home page body is empty"
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
        val buildFailures = mutableListOf<String>()

        // Resolve image URLs sequentially — several sources call a server endpoint here
        // (MangaDex's At-Home), and that endpoint may be rate-limited. The resolved URL is
        // written back onto the page so the image request is built against it.
        val resolved = pages.take(config.pageLimit).mapIndexedNotNull { index, page ->
            try {
                httpSource.resolveImageUrl(page)
                index to httpSource.getImageRequest(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildFailures += "page[$index] image request could not be built: ${e.message ?: e::class.java.simpleName}"
                null
            }
        }

        // Download and decode concurrently — the images are independent resources, and OkHttp's
        // dispatcher plus any source rate-limiter still governs the actual HTTP calls, so
        // concurrent launch does not bypass per-host limits. pageLimit (default 3) caps the
        // concurrency to a handful of calls.
        return buildFailures + coroutineScope {
            resolved.map { (index, request) ->
                async(Dispatchers.IO) {
                    probeOneImage(httpSource, index, request)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun probeOneImage(
        httpSource: HttpSource,
        index: Int,
        request: Request,
    ): String? = try {
        httpSource.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                return "page[$index] image returned HTTP ${response.code}"
            }
            val type = contentTypeOf(response)
            val bytes = response.body.bytes()
            imageDecodeNote(index, bytes, type)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "page[$index] image request failed: ${e::class.java.simpleName}: ${e.message}"
    }

    private fun mangaNotes(page: MangasPage) = Checks.mangaPage(page)

    private fun chapterNotes(update: SMangaUpdate) = Checks.chapters(update.chapters)

    private fun pageNotes(pages: List<Page>) = Checks.pages(pages)

    /**
     * The chapter the `pages` scenario asks for: the earliest numbered one.
     *
     * A site's newest chapter is the one most often behind a paywall - a free promo chapter ages
     * into a paid one, which is exactly what happened to Comikey's - while a first chapter is the
     * sample a site tends to leave open. Ordering is not part of the source contract, so the pick
     * is by `chapter_number` rather than by list position, and unnumbered entries (specials,
     * oneshots) only stand in when the list has nothing numbered at all.
     */
    private fun chapterToProbe(chapters: List<SChapter>): SChapter? =
        chapters.filter { it.chapter_number > 0 }.minByOrNull { it.chapter_number }
            ?: chapters.firstOrNull()

    private fun mangaSamples(page: MangasPage) = page.mangas.take(config.mangaLimit)
        .map { "${it.title} -> ${it.url}" }

    private fun chapterSamples(update: SMangaUpdate) = update.chapters.take(config.mangaLimit)
        .map { "${it.name} -> ${it.url}" }

    private fun pageSamples(pages: List<Page>) = pages.take(config.pageLimit)
        .map { it.imageUrl ?: it.url }

    /**
     * Runs [block], and asks again once if the site answered HTTP 429.
     *
     * A single 429 is usually a burst limit that clears immediately - three of the modules that
     * reported one passed untouched on a re-run minutes later - so one bounded retry removes the
     * noise without hiding a site that is genuinely refusing the sweep. That case still ends as
     * `THROTTLED`, and the retry is what tells the two apart.
     *
     * The wait prefers the server's own `Retry-After` and is capped, so a site asking for an hour
     * cannot stretch a shard. Both attempts share the scenario's deadline, so a retry can never
     * double the time a scenario is allowed.
     */
    private suspend fun <T> retryingThrottle(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        if (e.code != 429) throw e
        delay(e.retryAfterSeconds?.times(1000)?.coerceIn(0, MAX_THROTTLE_BACKOFF_MS) ?: DEFAULT_THROTTLE_BACKOFF_MS)
        block()
    }

    // internal for tests: the cancellation-vs-teardown distinction is tested directly.
    internal suspend fun <T> capture(
        name: String,
        notes: (T) -> List<String> = { emptyList() },
        samples: (T) -> List<String> = { emptyList() },
        /** The site this scenario talks to, so a throttle on it stops the rest of the run asking. */
        host: String? = null,
        block: suspend () -> T,
    ): Captured<T> {
        if (host != null && host in throttledHosts) {
            return Captured(
                null,
                ScenarioResult(name, Status.SKIP, 0, "$host answered HTTP 429 earlier in this run"),
            )
        }

        val start = System.nanoTime()
        return when (val outcome = withDeadline(config.timeout) { retryingThrottle(block) }) {
            DeadlineOutcome.Expired -> Captured(
                null,
                ScenarioResult(name, Status.FAIL, elapsedMs(start), "timed out after ${config.timeout.seconds}s"),
            )

            is DeadlineOutcome.Failed -> {
                val e = outcome.error
                // A CancellationException here came from the block itself - an extension's own
                // withTimeout, say, whose TimeoutCancellationException is one. The block runs on
                // its own thread under its own runBlocking, so its cancellation is isolated from
                // the run's; and the run being torn down never reaches this branch, because
                // withDeadline rethrows caller-side cancellation before it becomes a Failed
                // outcome. Rethrowing it anyway used to kill the whole module, silently dropping
                // every other source in it.
                if (TRACE) e.printStackTrace()
                val diagnosis = Checks.diagnose(e)
                if (diagnosis.status == Status.THROTTLED && host != null) throttledHosts += host
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

            is DeadlineOutcome.Value -> {
                val value = outcome.value
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
            }
        }
    }

    private fun worst(scenarios: List<ScenarioResult>): Status =
        scenarios.maxByOrNull { it.status.severity }?.status ?: Status.SKIP

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private fun gitCommit(root: Path): String? = runCatching {
        val process = ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
        try {
            process.inputStream.bufferedReader().readText().trim().ifBlank { null }
        } finally {
            // A no-op on the happy path - EOF means git already exited - but it keeps a read that
            // was interrupted mid-flight from leaving the child running out its natural life.
            process.destroyForcibly()
        }
    }.getOrNull()
}



/**
 * How long to wait before the single retry that follows an HTTP 429.
 *
 * Used only when the site sends no `Retry-After`. The cap applies either way: a site that asks for
 * an hour would otherwise park a shard, and the point of the retry is to absorb a burst limit, not
 * to wait out a real one.
 */
private const val DEFAULT_THROTTLE_BACKOFF_MS = 1_000L
private const val MAX_THROTTLE_BACKOFF_MS = 10_000L
