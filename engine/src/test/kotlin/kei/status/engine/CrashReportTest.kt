package kei.status.engine

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportTest {

    private fun config(module: String = ":src:en:example") = HarnessConfig(
        sourceRoot = Path.of("/extensions-source"),
        module = module,
        scratchRoot = Path.of("/tmp/kei-status-test"),
    )

    @Test
    fun `a missing Android class reports UNSUPPORTED, not FAIL`() {
        val report = HarnessRunner.crashReport(config(), NoClassDefFoundError("android/webkit/WebView"))

        assertEquals(Status.UNSUPPORTED, report.status)
        assertEquals("run", report.sources.single().scenarios.single().name)
        assertEquals(":src:en:example", report.module)
    }

    @Test
    fun `a generic crash reports FAIL with the diagnosis message`() {
        val report = HarnessRunner.crashReport(config(), IllegalStateException("boom"))

        assertEquals(Status.FAIL, report.status)
        assertEquals("boom", report.sources.single().scenarios.single().message)
    }

    @Test
    fun `a wrapped platform error classifies through the cause chain`() {
        val report = HarnessRunner.crashReport(
            config(),
            ExceptionInInitializerError(
                NoSuchMethodError("'byte[] android.util.Base64.decode(byte[], int)'"),
            ),
        )

        assertEquals(Status.UNSUPPORTED, report.status)
    }
}
