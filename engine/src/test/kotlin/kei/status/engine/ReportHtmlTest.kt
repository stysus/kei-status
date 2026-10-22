package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page is built from extension output, which is third-party data: a source name, a scraped URL
 * or an exception message containing markup must be shown as text, never interpreted.
 */
class ReportHtmlTest {

    private fun report(
        module: String,
        status: Status,
        sourceName: String = "Source",
        message: String? = null,
        notes: List<String> = emptyList(),
        scenarios: List<ScenarioResult> = listOf(
            ScenarioResult("connectivity", status, 12, message, notes),
        ),
    ) = RunReport(
        generatedAt = "2026-01-01T00:00:00Z",
        module = module,
        sourceRoot = "/tmp/extensions-source",
        extensionCommit = "abc1234",
        sources = listOf(
            SourceReport(
                sourceId = 1,
                name = sourceName,
                lang = "en",
                baseUrl = "https://example.test",
                status = status,
                scenarios = scenarios,
            ),
        ),
    )

    /** Two sources, so the module needs a row each and cannot collapse into its header. */
    private fun twoSources() = listOf(
        SourceReport(1, "First Site", "en", "https://a.test", Status.PASS, emptyList()),
        SourceReport(2, "Second Site", "id", "https://b.test", Status.PASS, emptyList()),
    )

    /** The markup of one module header, which is where its identity is decided. */
    private fun headerOf(html: String) =
        html.substringAfter("<summary class=\"module-head\">").substringBefore("</summary>")

    @Test
    fun `markup from an extension is escaped`() {
        val html = ReportHtml.site(
            listOf(
                report(
                    module = ":src:en:evil",
                    status = Status.FAIL,
                    sourceName = "<script>alert('x')</script>",
                    message = "5 < 6 && \"quoted\"",
                    notes = listOf("<img src=x onerror=alert(1)>"),
                ).copy(
                    changes = listOf(
                        StatusChange("<script>bad()</script>", "en", "pages", Status.PASS, Status.FAIL),
                    ),
                ),
            ),
        )

        assertFalse("a raw script tag reached the page", html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"))
        assertTrue(html.contains("5 &lt; 6 &amp;&amp; &quot;quoted&quot;"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("&lt;script&gt;bad()&lt;/script&gt;"))

        // Escaping must not strip the page's own script, or a long report becomes unusable.
        assertTrue(html.contains("document.getElementById('q')"))
        assertTrue(html.contains("</script>"))
    }

    @Test
    fun `modules are ordered worst first`() {
        val html = ReportHtml.site(
            listOf(
                report(":src:en:good", Status.PASS),
                report(":src:en:broken", Status.FAIL),
                report(":src:en:blocked", Status.BLOCKED),
                report(":src:en:skipped", Status.SKIP),
            ),
        )

        val fail = html.indexOf(":src:en:broken")
        val blocked = html.indexOf(":src:en:blocked")
        val pass = html.indexOf(":src:en:good")
        val skip = html.indexOf(":src:en:skipped")

        assertTrue("FAIL should lead", fail in 0..<blocked)
        assertTrue("BLOCKED should precede PASS", blocked < pass)
        assertTrue("PASS should precede SKIP", pass < skip)
    }

    @Test
    fun `every scenario is rendered with its duration`() {
        val html = ReportHtml.site(
            listOf(
                report(
                    module = ":src:en:aurora",
                    status = Status.PASS,
                    scenarios = listOf(
                        ScenarioResult("metadata", Status.PASS, 11),
                        ScenarioResult("pages", Status.WARN, 1234, notes = listOf("page[0] has no image")),
                    ),
                ),
            ),
        )

        assertTrue(html.contains(">metadata<"))
        assertTrue(html.contains(">pages<"))
        assertTrue(html.contains("11ms"))
        assertTrue(html.contains("1234ms"))
        assertTrue(html.contains("page[0] has no image"))
    }

    @Test
    fun `totals count each status and filter by it`() {
        val html = ReportHtml.site(
            listOf(
                report(":src:en:a", Status.PASS),
                report(":src:en:b", Status.PASS),
                report(":src:en:c", Status.BLOCKED),
            ),
        )

        assertTrue("2 passing sources not counted", html.contains("<b>2</b>"))
        assertTrue("1 blocked source not counted", html.contains("<b>1</b>"))
        // The tally is the filter for its status, so it has to be a control the script can bind to.
        assertTrue(
            "totals are not clickable filters",
            html.contains("class=\"total\" data-filter=\"blocked\""),
        )
    }

    @Test
    fun `an empty run still renders a page`() {
        val html = ReportHtml.site(emptyList())
        assertTrue(html.contains("No reports were produced."))
        assertTrue(html.contains("0 module(s)"))
    }

    @Test
    fun `the default title is the one the constant names`() {
        // The CLI and the publish script each used to spell the title out, so a rename in one place
        // left the page titled something the other never mentioned.
        val html = ReportHtml.site(emptyList())
        assertTrue(html.contains("<title>${ReportHtml.DEFAULT_TITLE}</title>"))
        assertTrue(html.contains("<h1>${ReportHtml.DEFAULT_TITLE}</h1>"))
    }

    @Test
    fun `every status has a pill class and a rule for it`() {
        // A status with no rule falls back to the plain pill: a coloured badge with no colour.
        val html = ReportHtml.site(Status.entries.map { report(":src:en:$it", it) })

        Status.entries.forEach { status ->
            val css = status.name.lowercase()
            assertTrue("no pill for $status", html.contains("class=\"pill $css\""))
            assertTrue("no stylesheet rule for $status", html.contains(".pill.$css"))
        }
    }

    @Test
    fun `the tally chips are ordered worst first`() {
        // The chips double as the page's filters, so their order is the order a reader triages in.
        // It comes from `Status.severity` rather than a second hand-written list, which is what this
        // pins: adding a status must place it here without anyone editing the renderer.
        val html = ReportHtml.site(Status.entries.map { report(":src:en:$it", it) })
        val filters = Regex("""data-filter="(\w+)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(listOf("fail", "blocked", "unsupported", "warn", "pass", "skip"), filters)
    }

    @Test
    fun `a module header shows the extension name and keeps its path for filtering`() {
        // The directory name is a slug: `allanime` ships a source that calls itself AllManga.
        val html = ReportHtml.site(
            listOf(report(":src:en:allanime", Status.PASS, sourceName = "AllManga")),
        )

        val header = headerOf(html)
        assertTrue("the extension name is missing from the header", header.contains(">AllManga<"))
        assertFalse("the Gradle path reached the header", header.contains(":src:"))
        assertFalse("the slug reached the header", header.contains(">allanime<"))

        // The path stays available to the filter without being displayed.
        assertTrue(html.contains("data-module=\":src:en:allanime\""))
    }

    @Test
    fun `a module with no name of its own falls back to its path`() {
        // Sources that disagree about their name, and a module that loaded nothing, both have to
        // produce a readable heading rather than an empty one.
        val disagreeing = ReportHtml.site(
            listOf(report(":src:all:multi", Status.PASS).copy(sources = twoSources())),
        )
        val nothingLoaded = ReportHtml.site(
            listOf(report(":src:en:ghost", Status.FAIL).copy(sources = emptyList())),
        )

        assertTrue(headerOf(disagreeing).contains(">Multi<"))
        assertTrue(headerOf(nothingLoaded).contains(">Ghost<"))
    }

    @Test
    fun `a bucket chip appears only when two multi-source headers share a name`() {
        // A single-source module shows its source's language, which is part of the row.
        val single = ReportHtml.site(listOf(report(":src:en:aurora", Status.PASS, sourceName = "Aurora")))
        assertTrue(headerOf(single).contains(">en<"))

        // For a module with several sources, the bucket is a disambiguator: shown only when the
        // name alone cannot tell two headers apart.
        val distinct = ReportHtml.site(
            listOf(
                report(":src:all:webtoons", Status.PASS, sourceName = "Webtoons.com").copy(sources = twoSources()),
                report(":src:all:mangadex", Status.PASS, sourceName = "MangaDex").copy(sources = twoSources()),
            ),
        )
        assertEquals(0, Regex("<span class=\"lang\">all</span>").findAll(distinct).count())

        val shared = ReportHtml.site(
            listOf(
                report(":src:en:dynasty", Status.PASS, sourceName = "Dynasty").copy(sources = twoSources()),
                report(":src:zh:dynasty", Status.PASS, sourceName = "Dynasty").copy(sources = twoSources()),
            ),
        )
        assertTrue(shared.contains("<span class=\"module-name\">Dynasty</span>\n<span class=\"lang\">en</span>"))
        assertTrue(shared.contains("<span class=\"module-name\">Dynasty</span>\n<span class=\"lang\">zh</span>"))
    }

    @Test
    fun `the upstream revision is shown once for the page`() {
        val html = ReportHtml.site(
            listOf(
                report(":src:en:a", Status.PASS),
                report(":src:en:b", Status.PASS),
                report(":src:en:c", Status.PASS),
            ),
        )

        // Once as the link target, once as its text: the header is where it must not appear.
        assertEquals(2, Regex("abc1234").findAll(html).count())
        assertTrue(html.contains("extensions at <a href=\"https://github.com/keiyoushi/extensions-source/commit/abc1234\""))

        // Shards can land on different revisions when a sweep straddles a commit.
        val straddled = ReportHtml.site(
            listOf(
                report(":src:en:a", Status.PASS),
                report(":src:en:b", Status.PASS).copy(extensionCommit = "def5678"),
            ),
        )
        assertTrue(straddled.contains("extensions from 2 revisions"))
    }

    @Test
    fun `a module with several sources gets a closed row each`() {
        val html = ReportHtml.site(listOf(report(":src:all:bundle", Status.PASS).copy(sources = twoSources())))

        // Native <details> rather than a scripted toggle, so the page still collapses without JS.
        // Closed by default: the first screen is the list of modules, not one module's scenarios.
        assertTrue("no collapsible module header", html.contains("<details class=\"module-details\">"))
        assertTrue(html.contains("<summary class=\"module-head\">"))
        assertTrue(html.contains("2 sources<"))
        assertFalse(html.contains("module-details single"))
        assertEquals(2, Regex("<details class=\"source\"").findAll(html).count())
        assertTrue(html.contains("<details class=\"source\" data-status=\"pass\" data-source>"))
    }

    @Test
    fun `a single-source module shows its scenarios without a second box`() {
        val html = ReportHtml.site(listOf(report(":src:en:aurora", Status.PASS, sourceName = "Aurora")))

        // The header is the source row: no nested <details>, no "1 source" count.
        assertTrue("no single-source module block", html.contains("<details class=\"module-details single\">"))
        assertFalse("a one-source module still wraps its only source in a box", html.contains("<details class=\"source\""))
        assertFalse(html.contains("1 source<"))

        // Its URL and language move into the header, and the table follows directly.
        val head = headerOf(html)
        assertTrue(head.contains("https://example.test"))
        assertTrue(head.contains(">en<"))
        assertTrue(html.contains("<div class=\"source-body\" data-status=\"pass\" data-source>"))
        assertTrue(html.contains(">connectivity<"))
    }

    @Test
    fun `a transition is rendered with both verdicts and not the comparison time`() {
        val changed = report(":src:en:a", Status.FAIL)
            .copy(
                comparedTo = "2025-12-01T00:00:00Z",
                changes = listOf(StatusChange("Aurora", "en", "pages", Status.PASS, Status.FAIL)),
            )
        val html = ReportHtml.site(listOf(changed))

        assertTrue(html.contains("class=\"changes\""))
        assertTrue(html.contains(">pages<"))
        assertTrue(html.contains(">PASS<"))
        assertTrue(html.contains(">FAIL<"))
        assertTrue(html.contains("&rarr;"))

        // When the comparison was taken is bookkeeping, not something a reader asked for.
        assertFalse(html.contains("2025-12-01"))
    }

    @Test
    fun `a source that appeared or vanished reads as new or gone`() {
        val changed = report(":src:en:a", Status.PASS)
            .copy(
                comparedTo = "2025-12-01T00:00:00Z",
                changes = listOf(
                    StatusChange("Added", "id", to = Status.PASS),
                    StatusChange("Removed", "en", from = Status.FAIL),
                ),
            )
        val html = ReportHtml.site(listOf(changed))

        assertTrue(html.contains(">new<"))
        assertTrue(html.contains(">gone<"))
    }

    @Test
    fun `the header shows when the page was checked`() {
        // The stored state can be weeks old while the page was refreshed last night, so the run's
        // clock is what the page reports.
        val stored = report(":src:en:a", Status.PASS).copy(generatedAt = "2025-12-01T00:00:00Z")
        val html = ReportHtml.site(listOf(stored), checkedAt = "2026-02-03T04:05:06.789Z")

        assertTrue(html.contains("last checked 2026-02-03 04:05 UTC"))
        assertFalse(html.contains("2025-12-01 00:00 UTC"))

        // An unreadable timestamp is shown as-is rather than failing the page.
        val odd = ReportHtml.site(listOf(stored), checkedAt = "whenever")
        assertTrue(odd.contains("last checked whenever"))
    }
}
