package kei.status.engine

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {

    @Test
    fun `manga without url or title is reported`() {
        val manga = SManga.create().apply {
            url = ""
            title = ""
        }

        val notes = Checks.mangaPage(MangasPage(listOf(manga), hasNextPage = false))

        assertTrue(notes.any { it.contains("blank url") })
        assertTrue(notes.any { it.contains("blank title") })
    }

    @Test
    fun `relative image url is allowed`() {
        // MangaDex keeps a server path in imageUrl and resolves it in getImageRequest.
        val pages = listOf(Page(0, imageUrl = "/data/1/1.png"))

        assertTrue(Checks.pages(pages).isEmpty())
    }

    @Test
    fun `page without any url is rejected`() {
        val pages = listOf(Page(0))

        val notes = Checks.pages(pages)

        assertTrue(notes.any { it.contains("neither imageUrl nor url") })
    }

    @Test
    fun `malformed absolute image url is rejected`() {
        val pages = listOf(Page(0, imageUrl = "https://exa mple.com/1.jpg"))

        val notes = Checks.pages(pages)

        assertTrue(notes.any { it.contains("not a valid http url") })
    }

    @Test
    fun `absolute image url passes`() {
        val pages = listOf(Page(0, imageUrl = "https://example.com/1.jpg"))

        assertTrue(Checks.pages(pages).isEmpty())
    }

    @Test
    fun `challenge is blocked rather than failed`() {
        val status = Checks.diagnose(AntiBotChallengeException(503, "example.com", "Cloudflare")).status

        assertEquals(Status.BLOCKED, status)
    }

    @Test
    fun `unsupported operation is skipped`() {
        val status = Checks.diagnose(UnsupportedOperationException()).status

        assertEquals(Status.SKIP, status)
    }

    @Test
    fun `missing android class is unsupported`() {
        val error = NoClassDefFoundError("android/webkit/WebView")

        val status = Checks.diagnose(error).status

        assertEquals(Status.UNSUPPORTED, status)
    }

    @Test
    fun `missing android os class is unsupported`() {
        // Looper is platform plumbing the harness does not emulate, same as WebView.
        val status = Checks.diagnose(NoClassDefFoundError("android/os/Looper")).status

        assertEquals(Status.UNSUPPORTED, status)
    }

    @Test
    fun `missing harness class is a failure`() {
        // A keiyoushi class missing means our classpath is wrong, not that the source needs Android.
        val status = Checks.diagnose(NoClassDefFoundError("keiyoushi/utils/WebViewKt")).status

        assertEquals(Status.FAIL, status)
    }

    @Test
    fun `missing quickjs is unsupported`() {
        val status = Checks.diagnose(NoClassDefFoundError("app/cash/quickjs/QuickJs")).status

        assertEquals(Status.UNSUPPORTED, status)
    }

    @Test
    fun `http error fails`() {
        val diagnosis = Checks.diagnose(HttpException(404))
        val status = diagnosis.status
        val message = diagnosis.message

        assertEquals(Status.FAIL, status)
        assertTrue(message.contains("404"))
    }

    @Test
    fun `wrapped cause is classified`() {
        val wrapped = RuntimeException("wrapped", AntiBotChallengeException(403, "example.com", "Cloudflare"))

        val status = Checks.diagnose(wrapped).status

        assertEquals(Status.BLOCKED, status)
    }
}
