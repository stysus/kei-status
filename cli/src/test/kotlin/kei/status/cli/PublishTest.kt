package kei.status.cli

import kei.status.engine.ReportStore
import kei.status.engine.RunReport
import kei.status.engine.SourceReport
import kei.status.engine.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.writeText

class PublishTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun report(module: String) = RunReport(
        generatedAt = "2026-10-26T00:00:00Z",
        module = module,
        sourceRoot = "",
        extensionCommit = null,
        sources = listOf(
            SourceReport(1, "Example", "en", "https://example.test", Status.PASS, emptyList()),
        ),
    )

    @Test
    fun `the page carries every stored module`() {
        val site = temporary.newFolder("site").toPath()
        val out = temporary.newFolder("out").toPath()
        ReportStore.save(site, report(":src:en:aurora"))
        ReportStore.save(site, report(":src:en:erisscans"))

        val code = publish(site, out, null, null, prune = false)

        assertEquals(0, code)
        val page = Files.readString(out.resolve("index.html"))
        assertTrue("aurora missing from the page", "data-module=\":src:en:aurora\"" in page)
        assertTrue("erisscans missing from the page", "data-module=\":src:en:erisscans\"" in page)
    }

    @Test
    fun `an unreadable entry is skipped, not fatal`() {
        val site = temporary.newFolder("site").toPath()
        val out = temporary.newFolder("out").toPath()
        ReportStore.save(site, report(":src:en:aurora"))
        site.resolve("src-en-broken.json").writeText("{ truncated")

        val code = publish(site, out, null, null, prune = false)

        assertEquals(0, code)
        val page = Files.readString(out.resolve("index.html"))
        assertTrue("data-module=\":src:en:aurora\"" in page)
        assertFalse("broken" in page)
    }

    @Test
    fun `--only narrows the page to the listed modules`() {
        val site = temporary.newFolder("site").toPath()
        val out = temporary.newFolder("out").toPath()
        ReportStore.save(site, report(":src:en:aurora"))
        ReportStore.save(site, report(":src:en:erisscans"))
        val only = temporary.newFile("only.txt").toPath().also {
            it.writeText(":src:en:erisscans\n")
        }

        val code = publish(site, out, null, only, prune = false)

        assertEquals(0, code)
        val page = Files.readString(out.resolve("index.html"))
        assertTrue("data-module=\":src:en:erisscans\"" in page)
        assertFalse("aurora was not in --only", "data-module=\":src:en:aurora\"" in page)
    }

    @Test
    fun `--prune without --only is refused before anything is written`() {
        val site = temporary.newFolder("site").toPath()
        val out = temporary.newFolder("out").toPath()

        assertEquals(2, publish(site, out, null, null, prune = true))
        assertFalse("no page may exist for a refused run", Files.exists(out.resolve("index.html")))
    }

    @Test
    fun `prune removes the dropped modules after the page is written`() {
        val site = temporary.newFolder("site").toPath()
        val out = temporary.newFolder("out").toPath()
        ReportStore.save(site, report(":src:en:aurora"))
        ReportStore.save(site, report(":src:en:erisscans"))
        val only = temporary.newFile("only.txt").toPath().also {
            it.writeText(":src:en:erisscans\n")
        }

        val code = publish(site, out, null, only, prune = true)

        assertEquals(0, code)
        assertFalse("pruned entry survived", Files.exists(site.resolve("src-en-aurora.json")))
        assertTrue("kept entry gone", Files.exists(site.resolve("src-en-erisscans.json")))
    }
}
