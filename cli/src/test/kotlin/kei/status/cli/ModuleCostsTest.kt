package kei.status.cli

import kei.status.engine.ReportStore
import kei.status.engine.RunReport
import kei.status.engine.ScenarioResult
import kei.status.engine.SourceReport
import kei.status.engine.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Duration

class ModuleCostsTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun options(dir: java.nio.file.Path) = Options(
        module = null, moduleList = null, discover = false, shard = null, plan = null,
        shards = null, sourceRoot = dir, output = null, html = null, store = dir,
        site = null, out = null, only = null, prune = false, title = null,
        timeout = Duration.ofSeconds(45), mangaLimit = 3, pageLimit = 3,
        probeImages = true, langs = emptySet(), help = false,
    )

    private fun report(module: String = ":src:en:aurora", durationMs: Long = 100) = RunReport(
        generatedAt = "2026-01-01T00:00:00Z",
        module = module,
        sourceRoot = "/tmp/extensions-source",
        extensionCommit = "abc1234",
        sources = listOf(
            SourceReport(1, "Aurora", "en", "https://example.test", Status.PASS,
                listOf(ScenarioResult("pages", Status.PASS, durationMs))),
        ),
    )

    @Test
    fun `the costs file wins over the store`() {
        val dir = temporary.newFolder().toPath()
        Files.writeString(dir.resolve("costs"), """{"${":src:en:aurora"}": 500000}""")
        ReportStore.save(dir, report(durationMs = 100))

        val costs = moduleCosts(options(dir))
        assertEquals(mapOf(":src:en:aurora" to 500000), costs)
    }

    @Test
    fun `an unreadable costs file falls back to the store`() {
        val dir = temporary.newFolder().toPath()
        Files.writeString(dir.resolve("costs"), "not json")
        ReportStore.save(dir, report(durationMs = 250))

        val costs = moduleCosts(options(dir))
        assertEquals(mapOf(":src:en:aurora" to 250), costs)
    }

    @Test
    fun `a missing costs file sums the store scenario durations`() {
        val dir = temporary.newFolder().toPath()
        ReportStore.save(dir, report(durationMs = 300))

        val costs = moduleCosts(options(dir))
        assertEquals(mapOf(":src:en:aurora" to 300), costs)
    }

    @Test
    fun `a run's measurements merge into the existing costs file`() {
        // A single-module run must not erase the sweep's other timings: the nightly sweep is the
        // one that measures everything, and a debugging session in between still costs one module.
        val dir = temporary.newFolder().toPath()
        Files.writeString(dir.resolve("costs"), """{":src:en:old": 42000, ":src:en:aurora": 100000}""")

        val merged = mergedCosts(dir, mapOf(":src:en:aurora" to 123000))

        assertEquals(mapOf(":src:en:old" to 42000L, ":src:en:aurora" to 123000L), merged)
    }

    @Test
    fun `an unreadable costs file merges into nothing rather than failing the run`() {
        val dir = temporary.newFolder().toPath()
        Files.writeString(dir.resolve("costs"), "not json")

        val merged = mergedCosts(dir, mapOf(":src:en:aurora" to 123000))

        assertEquals(mapOf(":src:en:aurora" to 123000L), merged)
    }
}
