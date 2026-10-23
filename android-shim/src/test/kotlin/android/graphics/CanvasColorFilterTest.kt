package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `colorFilter` is reached through the fluent setter `ColorFilter setColorFilter(ColorFilter)`,
 * not through a Kotlin property. The descriptor is exercised at compile time by extension bytecode
 * (see PlatformLinkageTest), so what is checked here is the behaviour behind it: a filter set on a
 * paint has to reach the pixels the canvas draws.
 */
class CanvasColorFilterTest {

    /** Swaps the red and green channels, so the result identifies the matrix unambiguously. */
    private val swapRedAndGreen = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    @Test
    fun `setColorFilter returns the filter it was given`() {
        val paint = Paint()

        assertSame(swapRedAndGreen, paint.setColorFilter(swapRedAndGreen))
        assertSame(swapRedAndGreen, paint.getColorFilter())
    }

    @Test
    fun `a filter reaches the drawn pixels`() {
        val source = solid(0xFF102030.toInt())

        assertEquals(0xFF102030.toInt(), drawnThrough(source, paint = null))
        assertEquals(0xFF201030.toInt(), drawnThrough(source, Paint().apply { setColorFilter(swapRedAndGreen) }))
    }

    @Test
    fun `clearing the filter restores the unfiltered pixels`() {
        val source = solid(0xFF405060.toInt())
        val paint = Paint().apply { setColorFilter(swapRedAndGreen) }

        paint.setColorFilter(null)

        assertNull(paint.getColorFilter())
        assertEquals(0xFF405060.toInt(), drawnThrough(source, paint))
    }

    @Test
    fun `reset clears the filter`() {
        val paint = Paint().apply { setColorFilter(swapRedAndGreen) }

        paint.reset()

        assertNull(paint.getColorFilter())
    }

    private fun solid(argb: Int): Bitmap =
        Bitmap.createBitmap(IntArray(4) { argb }, 2, 2, Bitmap.Config.ARGB_8888)

    private fun drawnThrough(source: Bitmap, paint: Paint?): Int {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, null, Rect(0, 0, source.width, source.height), paint)

        val pixels = IntArray(source.width * source.height)
        output.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return pixels[0]
    }
}
