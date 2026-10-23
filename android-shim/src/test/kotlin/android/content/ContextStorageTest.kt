package android.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The storage members that were missing for real sources: the Kemono theme keys its OkHttp cache on
 * `externalCacheDir`, and TCB Scans joins `applicationInfo.dataDir` with `shared_prefs`.
 */
class ContextStorageTest {

    @Test
    fun `external cache is a real directory inside the attached root`() {
        val context = Context()
        val root = Files.createTempDirectory("walawe-context").toFile()
        try {
            context.attachRoot(root)

            val externalCache = context.getExternalCacheDir()

            assertTrue("external cache must exist", externalCache.isDirectory)
            assertTrue(
                "external cache must live under the run's scratch root, not the real user home",
                externalCache.canonicalPath.startsWith(root.canonicalPath),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `application info points dataDir at the attached root`() {
        val context = Context()
        val root = Files.createTempDirectory("walawe-context").toFile()
        try {
            context.attachRoot(root)

            val info = context.getApplicationInfo()

            assertEquals(root.absolutePath, info.dataDir)
            assertEquals(context.getPackageName(), info.packageName)
        } finally {
            root.deleteRecursively()
        }
    }
}
