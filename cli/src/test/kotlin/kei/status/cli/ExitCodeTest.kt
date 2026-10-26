package kei.status.cli

import kei.status.engine.ReportStore
import kei.status.engine.RunReport
import kei.status.engine.SourceReport
import kei.status.engine.Status
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exit code is how a CI shard admits failure. A run that checked nothing did not succeed:
 * every module was dropped by an error, and the shard job must fail rather than record a marker
 * that says it ran, which would let a broken harness look like a quiet night.
 */
class ExitCodeTest {

    @Test
    fun `a run that checked nothing fails`() {
        assertEquals(1, exitCode(emptyList()))
    }

    @Test
    fun `a fail anywhere in the run fails`() {
        val failing = RunReport(
            generatedAt = "2026-10-25T00:00:00Z",
            module = ":src:en:example",
            sourceRoot = "/extensions-source",
            extensionCommit = null,
            sources = listOf(
                SourceReport(
                    name = "Example",
                    lang = "en",
                    sourceId = 1,
                    baseUrl = "https://example.com",
                    status = Status.FAIL,
                    scenarios = emptyList(),
                ),
            ),
        )
        assertEquals(1, exitCode(listOf(failing to null)))
    }

    @Test
    fun `a run with no failing module exits zero`() {
        val healthy = RunReport(
            generatedAt = "2026-10-25T00:00:00Z",
            module = ":src:en:example",
            sourceRoot = "/extensions-source",
            extensionCommit = null,
            sources = emptyList(),
        )
        assertEquals(0, exitCode(listOf(healthy to null)))
    }

    @Test
    fun `blocked and throttled modules do not fail the run`() {
        val report = RunReport(
            generatedAt = "2026-10-25T00:00:00Z",
            module = ":src:en:example",
            sourceRoot = "/extensions-source",
            extensionCommit = null,
            sources = listOf(
                SourceReport(1, "Blocked", "en", "https://example.com", Status.BLOCKED, emptyList()),
                SourceReport(2, "Throttled", "en", "https://example.com", Status.THROTTLED, emptyList()),
                SourceReport(3, "Unsupported", "en", "https://example.com", Status.UNSUPPORTED, emptyList()),
            ),
        )
        assertEquals(0, exitCode(listOf(report to null)))
    }
}
