package android.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the text path the shared text-interceptor lib takes: turn a source's HTML synopsis into
 * plain text, lay it out at a fixed width, and draw it into a bitmap.
 */
class TextLayoutTest {

    private val paint = TextPaint().apply {
        textSize = 14f
        isAntiAlias = true
    }

    private fun layout(text: String, width: Int = 120): StaticLayout = StaticLayout(
        text,
        paint,
        width,
        Layout.Alignment.ALIGN_NORMAL,
        1.1f,
        2f,
        true,
    )

    @Test
    fun `html is reduced to text with a blank line between blocks`() {
        assertEquals("one\n\ntwo", Html.fromHtml("<p>one</p><p>two</p>").toString())
    }

    @Test
    fun `html keeps inline markup as text and decodes entities`() {
        assertEquals("a < b & c", Html.fromHtml("a &lt; b &amp; c").toString())
        assertEquals("bold text", Html.fromHtml("<b>bold</b> <i>text</i>").toString())
        assertEquals("a\nb", Html.fromHtml("a<br>b").toString())
    }

    @Test
    fun `html collapses runs of whitespace the way a renderer would`() {
        assertEquals("a b", Html.fromHtml("<div>a\n\n   b</div>").toString())
    }

    @Test
    fun `a long paragraph wraps into several lines`() {
        val short = layout("one line")
        val long = layout("two words ".repeat(40))

        assertEquals(1, short.getLineCount())
        assertTrue("expected several lines, got ${long.getLineCount()}", long.getLineCount() > 3)
        assertTrue(long.getHeight() > short.getHeight())
    }

    @Test
    fun `height covers every line so the caller can size its bitmap`() {
        val oneLine = layout("a")
        val twoLines = layout("a\nb")

        assertEquals(twoLines.getHeight(), 2 * oneLine.getHeight())
    }

    @Test
    fun `drawing puts ink inside the layout's width`() {
        val width = 120
        val bitmap = Bitmap.createBitmap(width, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val text = layout("wrapping text that has to be drawn", width)
        text.draw(canvas)

        val pixels = IntArray(width * 200)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, 200)
        val inked = pixels.count { it != Color.WHITE }
        assertTrue("nothing was drawn", inked > 0)

        // Below the reported height the page must stay blank, or the caller's height is a lie.
        val undrawn = (text.getHeight() until 200).sumOf { row ->
            (0 until width).count { column -> pixels[row * width + column] != Color.WHITE }
        }
        assertEquals(0, undrawn)
    }

    @Test
    fun `empty text produces a drawable layout of one empty line`() {
        val empty = layout("")

        assertEquals(1, empty.getLineCount())
        assertTrue(empty.getHeight() > 0)
    }
}
