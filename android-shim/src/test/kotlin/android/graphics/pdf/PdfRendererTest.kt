package android.graphics.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class PdfRendererTest {

    /**
     * The reader extensions build their page lists from `pageCount` and re-render single pages to
     * JPEG through `openPage`/`render`, all via a `ParcelFileDescriptor` they opened on a cached
     * file. This pins the descriptors those call sites link against.
     */
    @Test
    fun `the surface the PDF readers link against is present`() {
        assertEquals(
            Int::class.javaPrimitiveType,
            PdfRenderer::class.java.getMethod("getPageCount").returnType,
        )
        PdfRenderer::class.java.getMethod(
            "openPage",
            Int::class.javaPrimitiveType,
        ).let { method ->
            assertEquals(PdfRenderer.Page::class.java, method.returnType)
        }
        PdfRenderer.Page::class.java.getMethod(
            "render",
            Bitmap::class.java,
            android.graphics.Matrix::class.java,
            android.graphics.Rect::class.java,
            Int::class.javaPrimitiveType,
        )
        PdfRenderer.Page::class.java.getMethod("getWidth")
        PdfRenderer.Page::class.java.getMethod("getHeight")
        assertEquals(
            ParcelFileDescriptor::class.java,
            ParcelFileDescriptor::class.java.getMethod("open", File::class.java, Int::class.javaPrimitiveType)
                .returnType,
        )
    }

    @Test
    fun `a two-page file reports its page count and page size`() {
        open(TWO_PAGE_PDF).use { renderer ->
            assertEquals(2, renderer.pageCount)
            renderer.openPage(0).use { page ->
                assertEquals(595, page.width)
                assertEquals(842, page.height)
            }
            renderer.openPage(1).close()
        }
    }

    @Test
    fun `an out-of-range page index is rejected`() {
        open(TWO_PAGE_PDF).use { renderer ->
            assertThrows(IllegalArgumentException::class.java) { renderer.openPage(2) }
            assertThrows(IllegalArgumentException::class.java) { renderer.openPage(-1) }
        }
    }

    @Test
    fun `render accepts the destination the reader prepared and leaves it painted`() {
        open(TWO_PAGE_PDF).use { renderer ->
            renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixel = IntArray(1)
                bitmap.getPixels(pixel, 0, 1, 0, 0, 1, 1)
                assertEquals(Color.WHITE, pixel[0])
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `closing the renderer closes the descriptor it was opened with`() {
        val descriptor = ParcelFileDescriptor.open(file(TWO_PAGE_PDF), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { assertEquals(2, it.pageCount) }
        assertThrows(IOException::class.java) { descriptor.handle.length() }
    }

    @Test
    fun `a file with no page tree is rejected, not miscounted`() {
        assertThrows(IOException::class.java) { open("not a pdf at all\n") }
    }

    @Test
    fun `an empty page list parses as zero pages and is rejected`() {
        // A catalog whose page tree declares nothing: the raw scan finds no /Type /Page objects
        // and a /Count of 0, which is the "not a PDF we can serve" answer for the reader.
        val emptyTree = "%PDF-1.4\n1 0 obj << /Type /Pages /Count 0 /Kids [] >> endobj\n"
        assertThrows(IOException::class.java) { open(emptyTree) }
    }

    @Test
    fun `a page token straddling the read boundary is counted exactly once`() {
        // The scanner reads in 1 MiB chunks with a 64-byte carry between them; this file puts
        // its only /Type /Page token across the first boundary, where a lost carry would drop
        // the page and a double count would see it twice.
        val pad = "%".repeat(1024 * 1024 - 9 - 5)
        val straddling = "%PDF-1.4\n$pad/Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]\n" +
            "2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj\n"

        open(straddling).use { renderer ->
            assertEquals(1, renderer.pageCount)
            renderer.openPage(0).use { page ->
                assertEquals(595, page.width)
                assertEquals(842, page.height)
            }
        }
    }

    private fun open(content: String): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(file(content), ParcelFileDescriptor.MODE_READ_ONLY))

    private fun file(content: String): File =
        File.createTempFile("shim", ".pdf", tempDir.toFile()).apply {
            writeText(content)
            deleteOnExit()
        }

    private companion object {
        // The shape the cached chapter files have: a catalog, a page tree with a Count, and
        // page objects each declaring the same MediaBox.
        val tempDir = createTempDirectory("pdf-renderer-test")

        val TWO_PAGE_PDF = (
            "%PDF-1.4\n" +
                "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n" +
                "2 0 obj << /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >> endobj\n" +
                "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >> endobj\n" +
                "4 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >> endobj\n" +
                "trailer << /Root 1 0 R >>\n%%EOF\n"
            )
    }
}
