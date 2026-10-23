package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The store is committed to the repository, so its two jobs are both about git: a module that did
 * not change must not produce a diff, and a module that did must produce a readable one.
 */
class ReportStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var store: Path

    private fun store(): Path {
        store = temporary.newFolder("data").toPath()
        return store
    }

    private fun report(
        generatedAt: String = "2026-01-01T00:00:00Z",
        module: String = ":src:en:aurora",
        sourceRoot: String = "/tmp/extensions-source",
        sources: List<SourceReport> = listOf(source()),
    ) = RunReport(
        generatedAt = generatedAt,
        module = module,
        sourceRoot = sourceRoot,
        extensionCommit = "abc1234",
        sources = sources,
    )

    private fun source(
        name: String = "Aurora",
        lang: String = "en",
        status: Status = Status.PASS,
        scenarios: List<ScenarioResult> = listOf(ScenarioResult("pages", status, 10)),
    ) = SourceReport(1, name, lang, "https://example.test", status, scenarios)

    @Test
    fun `the first save records the report`() {
        val dir = store()
        val saved = ReportStore.save(dir, report())

        assertTrue(saved.written)
        assertNull("nothing to compare against on the first run", saved.report.comparedTo)
        assertTrue(saved.changes.isEmpty())
        assertTrue(Files.isRegularFile(dir.resolve("src-en-aurora.json")))
    }

    @Test
    fun `a save reports the file it targeted, not a guessed one`() {
        // The CLI used to print a path rebuilt from a hardcoded `data`, so `--store out` reported a
        // file it never touched. The save knows where it wrote; that is what it hands back.
        val dir = temporary.newFolder("somewhere-else").toPath()

        val saved = ReportStore.save(dir, report())

        assertEquals(dir.resolve("src-en-aurora.json"), saved.path)
    }

    @Test
    fun `an unchanged module is not rewritten`() {
        val dir = store()
        ReportStore.save(dir, report(generatedAt = "2026-01-01T00:00:00Z"))

        // A later run with a new clock reading but the same state.
        val again = ReportStore.save(dir, report(generatedAt = "2026-01-02T00:00:00Z"))

        assertFalse("a passing module must not create a diff", again.written)
        assertTrue(again.changes.isEmpty())

        val onDisk = ReportStore.read(dir, ":src:en:aurora")!!
        assertEquals("2026-01-01T00:00:00Z", onDisk.generatedAt)
    }

    @Test
    fun `what a run happens to observe is not a state change`() {
        // A store that churned on every run would be a diff a day with nothing to read in it. Only
        // the verdicts belong in it: not timings, not the manga a site returns, not where the tree
        // was checked out, not the upstream commit that happens to be current, and not the order
        // the module lists its sources in.
        val dir = store()
        ReportStore.save(dir, report())

        val variants = mapOf(
            "checkout path" to report(sourceRoot = "/home/me/Coding/extensions-source"),
            "duration and samples" to report(
                sources = listOf(
                    source(
                        scenarios = listOf(
                            ScenarioResult("pages", Status.PASS, 999, samples = listOf("B -> /b")),
                        ),
                    ),
                ),
            ),
            "extension commit" to report().copy(extensionCommit = "def5678"),
        )
        variants.forEach { (what, variant) ->
            assertFalse("$what churned the store", ReportStore.save(dir, variant).written)
        }

        val ordered = report(
            sources = listOf(source(name = "MangaDex", lang = "en"), source(name = "MangaDex", lang = "id")),
        )
        ReportStore.save(dir, ordered)
        val reversed = report(
            sources = listOf(source(name = "MangaDex", lang = "id"), source(name = "MangaDex", lang = "en")),
        )
        assertFalse("source order churned the store", ReportStore.save(dir, reversed).written)
    }

    @Test
    fun `a changed scenario is recorded`() {
        val dir = store()
        ReportStore.save(dir, report())

        val saved = ReportStore.save(
            dir,
            report(
                generatedAt = "2026-01-02T00:00:00Z",
                sources = listOf(source(status = Status.FAIL)),
            ),
        )

        assertTrue(saved.written)
        assertEquals(
            listOf(StatusChange("Aurora", "en", "pages", Status.PASS, Status.FAIL)),
            saved.changes,
        )
        assertEquals("2026-01-01T00:00:00Z", saved.report.comparedTo)
    }

    @Test
    fun `a changed scenario does not also report the source verdict`() {
        // "pages PASS -> FAIL" already explains "source PASS -> FAIL"; saying both is noise.
        val dir = store()
        ReportStore.save(dir, report())

        val saved = ReportStore.save(dir, report(sources = listOf(source(status = Status.FAIL))))

        assertEquals(1, saved.changes.size)
        assertEquals("pages", saved.changes.single().scenario)
    }

    @Test
    fun `a new and a vanished scenario are both recorded`() {
        val dir = store()
        ReportStore.save(
            dir,
            report(sources = listOf(source(scenarios = listOf(ScenarioResult("pages", Status.PASS, 1))))),
        )

        val saved = ReportStore.save(
            dir,
            report(sources = listOf(source(scenarios = listOf(ScenarioResult("images", Status.PASS, 1))))),
        )

        val byScenario = saved.changes.associateBy { it.scenario }
        assertEquals(Status.PASS, byScenario.getValue("images").to)
        assertNull(byScenario.getValue("images").from)
        assertEquals(Status.PASS, byScenario.getValue("pages").from)
        assertNull(byScenario.getValue("pages").to)
    }

    @Test
    fun `a new language variant is recorded as a new source`() {
        val dir = store()
        ReportStore.save(dir, report(sources = listOf(source(name = "MangaDex", lang = "en"))))

        val saved = ReportStore.save(
            dir,
            report(
                sources = listOf(
                    source(name = "MangaDex", lang = "en"),
                    source(name = "MangaDex", lang = "id"),
                ),
            ),
        )

        val change = saved.changes.single()
        assertEquals("id", change.lang)
        assertNull(change.scenario)
        assertNull(change.from)
    }

    @Test
    fun `a changed reason is recorded even when the verdict holds`() {
        // Still failing, but for a different reason: that is worth seeing in the diff.
        val dir = store()
        ReportStore.save(
            dir,
            report(sources = listOf(source(status = Status.FAIL, scenarios = listOf(ScenarioResult("pages", Status.FAIL, 1, "timed out"))))),
        )

        val again = ReportStore.save(
            dir,
            report(sources = listOf(source(status = Status.FAIL, scenarios = listOf(ScenarioResult("pages", Status.FAIL, 1, "401 Unauthorized"))))),
        )

        assertTrue(again.written)
    }

    @Test
    fun `a corrupt entry is ignored and replaced`() {
        val dir = store()
        Files.writeString(dir.resolve("src-en-aurora.json"), "{ this is not json")

        val saved = ReportStore.save(dir, report())

        assertTrue(saved.written)
        assertTrue(saved.changes.isEmpty())
        assertEquals(1, ReportStore.readAll(dir).size)
    }

    @Test
    fun `readAll returns every stored module`() {
        val dir = store()
        ReportStore.save(dir, report(module = ":src:en:aurora"))
        ReportStore.save(dir, report(module = ":src:tr:hattorimanga"))

        val modules = ReportStore.readAll(dir).map { it.module }.sorted()

        assertEquals(listOf(":src:en:aurora", ":src:tr:hattorimanga"), modules)
    }

    @Test
    fun `prune removes the entries of modules that no longer exist`() {
        val dir = store()
        ReportStore.save(dir, report(module = ":src:en:aurora"))
        ReportStore.save(dir, report(module = ":src:tr:hattorimanga"))

        val removed = ReportStore.prune(dir, setOf(":src:en:aurora"))

        assertEquals(listOf(dir.resolve("src-tr-hattorimanga.json")), removed)
        assertTrue("the surviving module was deleted", Files.isRegularFile(dir.resolve("src-en-aurora.json")))
        assertFalse("the removed module is still in the store", Files.exists(dir.resolve("src-tr-hattorimanga.json")))
    }

    @Test
    fun `prune keeps everything when every stored module still exists`() {
        val dir = store()
        ReportStore.save(dir, report(module = ":src:en:aurora"))
        ReportStore.save(dir, report(module = ":src:tr:hattorimanga"))

        val removed = ReportStore.prune(dir, setOf(":src:en:aurora", ":src:tr:hattorimanga"))

        assertTrue("nothing should have been removed", removed.isEmpty())
        assertEquals(2, ReportStore.readAll(dir).size)
    }

    @Test
    fun `prune removes an entry it cannot read, because the name decides`() {
        // readAll skips a corrupt file without saying which module it held, so pruning on contents
        // would leave exactly the entries nobody can account for. The file name is the module.
        val dir = store()
        Files.writeString(dir.resolve("src-en-gone.json"), "{ this is not json")

        val removed = ReportStore.prune(dir, setOf(":src:en:aurora"))

        assertEquals(listOf(dir.resolve("src-en-gone.json")), removed)
        assertFalse(Files.exists(dir.resolve("src-en-gone.json")))
    }

    @Test
    fun `prune ignores files that are not store entries`() {
        val dir = store()
        ReportStore.save(dir, report(module = ":src:en:aurora"))
        Files.writeString(dir.resolve("index.html"), "<html></html>")

        assertTrue(ReportStore.prune(dir, setOf(":src:en:aurora")).isEmpty())
        assertTrue("a rendered page is not a store entry", Files.isRegularFile(dir.resolve("index.html")))
    }

    @Test
    fun `the committed file is readable json with no machine-specific path`() {
        val dir = store()
        ReportStore.save(dir, report(sourceRoot = "/home/someone/Coding/extensions-source"))

        val text = Files.readString(dir.resolve("src-en-aurora.json"))

        // It is committed, so a reviewer must be able to read a diff of it.
        assertTrue("the file should be multi-line", text.contains("\n    \""))
        assertTrue(text.contains("\"module\": \":src:en:aurora\""))
        assertFalse("a machine-specific path must not be committed", text.contains("/home/someone"))
    }

    @Test
    fun `the file name comes from the module path`() {
        assertEquals("src-en-aurora.json", ReportStore.path(store(), ":src:en:aurora").fileName.toString())
    }

    @Test
    fun `a report takes its name and language from its sources or its path`() {
        // The header shows what the extension calls itself, and only falls back to the path when
        // there is no single name to show.
        val loaded = report().copy(module = ":src:all:webtoons")
        assertEquals("Aurora", loaded.displayName)
        assertEquals("all", loaded.lang)

        val nothingLoaded = report().copy(module = ":src:en:allanime", sources = emptyList())
        assertEquals("Allanime", nothingLoaded.displayName)
        assertEquals("en", nothingLoaded.lang)

        val outsideTree = report().copy(module = "webtoons")
        assertEquals("Aurora", outsideTree.displayName)
        assertEquals("", outsideTree.lang)
    }
}
