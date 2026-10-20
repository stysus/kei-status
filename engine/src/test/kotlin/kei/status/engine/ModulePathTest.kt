package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

/**
 * `--module` accepts a Gradle path or a directory. Both must end up as a Gradle path in the report,
 * because the report is published and an absolute path would mean nothing to a reader.
 */
class ModulePathTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun reportFor(module: String, root: Path): RunReport {
        val runner = HarnessRunner(
            HarnessConfig(
                sourceRoot = root,
                module = module,
                scratchRoot = temporary.newFolder("scratch").toPath(),
            ),
        )
        return runner.run()
    }

    @Test
    fun `a gradle path is kept as given`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val report = reportFor(":src:en:aurora", root)
        assertEquals(":src:en:aurora", report.module)
    }

    @Test
    fun `a directory under the source root becomes a gradle path`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val report = reportFor(root.resolve("src/en/aurora").toString(), root)
        assertEquals(":src:en:aurora", report.module)
    }

    @Test
    fun `a directory outside the source root is left alone`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val elsewhere = temporary.newFolder("elsewhere").toPath()
        val report = reportFor(elsewhere.toString(), root)
        assertEquals(elsewhere.toAbsolutePath().normalize().toString(), report.module)
    }

    @Test
    fun `a module that was never compiled reports a failed load`() {
        // CI runs over a list of modules; one that fails to compile must not abort the run.
        val root = temporary.newFolder("extensions-source").toPath()
        val report = reportFor(":src:en:nevercompiled", root)

        assertEquals(Status.FAIL, report.status)
        assertEquals(1, report.sources.size)
        assertEquals("load", report.sources.first().scenarios.first().name)
    }
}
