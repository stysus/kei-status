package android.graphics.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stand-in for android.graphics.pdf.PdfRenderer.
 *
 * The readers that publish chapters as PDF files count the pages with `pageCount` to build
 * their page lists, and re-render single pages to JPEG for the image requests - both through
 * this class and a [ParcelFileDescriptor]. Android's renderer runs on the platform's PDF
 * engine; the stand-in scans the file's page tree for the page count and the first
 * `MediaBox` for the page size, which is everything the call sites read.
 *
 * A real render would need a PDF rasterizer; every call site paints its destination white
 * before calling `render`, so the call is accepted and the destination is left as the caller
 * prepared it. The sweep's image probe then verifies that the PDF pipeline downloads,
 * opens, and re-encodes a chapter end to end - not what its pages depict.
 */
class PdfRenderer(private val descriptor: ParcelFileDescriptor) : Closeable {

    private val pages: PageTree = scan(descriptor)

    /** The number of pages the file's page tree declares. */
    val pageCount: Int
        get() = pages.count

    fun openPage(index: Int): Page {
        if (index < 0 || index >= pages.count) {
            throw IllegalArgumentException("page index ($index) is out of range [0, ${pages.count})")
        }
        return Page(index, pages.width, pages.height)
    }

    /** Android's renderer closes the descriptor it was given; the stand-in does the same. */
    override fun close() {
        descriptor.close()
    }

    class Page internal constructor(val index: Int, val width: Int, val height: Int) : Closeable {

        // shortcut: renders nothing - every call site paints its destination white first, and
        // the image probe checks the pipeline, not the pixels; upgrade with a real rasterizer
        fun render(destination: Bitmap?, transformation: Matrix?, clip: Rect?, renderMode: Int) = Unit

        override fun close() = Unit

        companion object {
            const val RENDER_MODE_FOR_DISPLAY: Int = 1
            const val RENDER_MODE_FOR_PRINT: Int = 2
        }
    }

    private class PageTree(val count: Int, val width: Int, val height: Int)

    /**
     * Counts the file's page objects and finds its first `MediaBox` by scanning the raw bytes,
     * in chunks with an overlap so a token straddling a boundary is read whole on the next
     * pass. A match is only counted when it ends inside the fresh bytes - a token wholly
     * inside the overlap was already counted by the pass that first saw it.
     *
     * The `Count` of the page tree is a fallback: a PDF whose page objects sit in compressed
     * object streams hides them from the raw scan, but its catalog still declares the total,
     * and the root's count is the largest on file.
     *
     * shortcut: tokens longer than the 64-byte overlap would be missed; every token in these
     * PDFs is well under it, and a miss falls back to the tree count
     */
    private fun scan(descriptor: ParcelFileDescriptor): PageTree {
        val typePage = Regex("/Type\\s*/Page\\b")
        val treeCount = Regex("/Count\\s+(\\d+)")
        val mediaBox = Regex(
            "/MediaBox\\s*\\[\\s*([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s*\\]",
        )

        var objects = 0
        var largestTree = 0
        var box: List<String>? = null
        val chunk = ByteArray(CHUNK)
        val handle = descriptor.handle

        handle.seek(0)
        var carry = ""
        while (true) {
            val read = handle.read(chunk)
            if (read <= 0) break
            val text = carry + String(chunk, 0, read, Charsets.ISO_8859_1)

            // The fresh bytes begin where the carried overlap ends; a match that ends before
            // that was already counted by the pass that first saw it.
            val freshStart = text.length - read
            objects += typePage.findAll(text).count { it.range.last >= freshStart }
            largestTree = max(
                largestTree,
                treeCount.findAll(text)
                    .filter { it.range.last >= freshStart }
                    .maxOfOrNull { it.groupValues[1].toInt() } ?: 0,
            )
            if (box == null) box = mediaBox.find(text)?.groupValues

            carry = text.takeLast(OVERLAP)
        }

        val count = max(objects, largestTree)
        if (count <= 0) throw IOException("no pages found: not a PDF, or one the shim cannot scan")

        val numbers = box?.drop(1)?.map { it.toFloatOrNull() ?: 0f }
        val width = if (numbers != null) ((numbers[2] - numbers[0]).roundToInt()) else DEFAULT_WIDTH
        val height = if (numbers != null) ((numbers[3] - numbers[1]).roundToInt()) else DEFAULT_HEIGHT
        return PageTree(count, max(1, width), max(1, height))
    }

    private companion object {
        const val CHUNK = 1024 * 1024

        /** Longer than any token the scanner looks for, so a straddling one is re-read whole. */
        const val OVERLAP = 64

        const val DEFAULT_WIDTH = 595
        const val DEFAULT_HEIGHT = 842
    }
}
