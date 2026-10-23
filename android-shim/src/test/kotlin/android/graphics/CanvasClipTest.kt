package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `clipRect` answers a question - "is anything still visible?" - rather than reporting whether the
 * clip changed. Android's contract is the former, and the extensions that paint their own speech
 * bubbles clip a bubble to its box, so the difference is observable: clipping to a superset of the
 * current clip returns true, and only an empty intersection returns false.
 */
class CanvasClipTest {

    @Test
    fun `clipRect reports a non-empty result`() {
        assertTrue(Canvas(blank()).clipRect(0f, 0f, 5f, 5f))
    }

    @Test
    fun `clipRect still reports true when the clip only grows`() {
        val canvas = Canvas(blank())

        canvas.clipRect(2f, 2f, 8f, 8f)

        assertTrue(canvas.clipRect(0f, 0f, 10f, 10f))
    }

    @Test
    fun `clipRect reports false once the intersection is empty`() {
        val canvas = Canvas(blank())

        canvas.clipRect(0f, 0f, 4f, 4f)

        assertFalse(canvas.clipRect(6f, 6f, 9f, 9f))
    }

    @Test
    fun `clipRect reports false for a zero-area rectangle`() {
        assertFalse(Canvas(blank()).clipRect(5f, 5f, 5f, 9f))
        assertFalse(Canvas(blank()).clipRect(5f, 5f, 9f, 5f))
    }

    @Test
    fun `clipRect restricts what is drawn`() {
        val bitmap = blank()
        val canvas = Canvas(bitmap)

        canvas.clipRect(0f, 0f, 5f, 5f)
        canvas.drawColor(0xFFFF0000.toInt())

        assertEquals(0xFFFF0000.toInt(), pixelAt(bitmap, 0, 0))
        assertEquals(0xFFFF0000.toInt(), pixelAt(bitmap, 4, 4))
        assertEquals(WHITE, pixelAt(bitmap, 5, 0))
        assertEquals(WHITE, pixelAt(bitmap, 0, 5))
    }

    @Test
    fun `the RectF overload clips the same region`() {
        val bitmap = blank()
        val canvas = Canvas(bitmap)

        assertTrue(canvas.clipRect(RectF(5f, 5f, 10f, 10f)))
        canvas.drawColor(0xFF00FF00.toInt())

        assertEquals(WHITE, pixelAt(bitmap, 0, 0))
        assertEquals(0xFF00FF00.toInt(), pixelAt(bitmap, 5, 5))
        assertEquals(0xFF00FF00.toInt(), pixelAt(bitmap, 9, 9))
    }

    @Test
    fun `restore puts the clip back`() {
        val bitmap = blank()
        val canvas = Canvas(bitmap)

        canvas.save()
        canvas.clipRect(0f, 0f, 5f, 5f)
        canvas.restore()
        canvas.drawColor(0xFF0000FF.toInt())

        assertEquals(0xFF0000FF.toInt(), pixelAt(bitmap, 9, 9))
    }

    private fun blank(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(WHITE)
    }

    private fun pixelAt(bitmap: Bitmap, x: Int, y: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels[y * bitmap.width + x]
    }

    private companion object {
        const val SIZE = 10
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
