package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rawkuma's TextOverlayInterceptor stretches SVG text to an exact width by measuring it and
 * setting [Paint.setTextScaleX] to the ratio, so both members have to exist with the framework's
 * descriptors and measure with the same font the canvas draws with.
 */
class PaintTest {

    @Test
    fun `text scale x defaults to one and is settable`() {
        val paint = Paint()

        assertEquals(1f, paint.textScaleX, 0f)

        paint.textScaleX = 1.5f
        assertEquals(1.5f, paint.textScaleX, 0f)
    }

    @Test
    fun `measure text is zero for empty text and grows with length`() {
        val paint = Paint()

        assertEquals(0f, paint.measureText(""), 0f)
        val short = paint.measureText("i")
        val long = paint.measureText("iii")

        assertTrue("expected $long > $short", long > short)
    }

    @Test
    fun `measure text applies the current text scale x`() {
        val paint = Paint()
        val base = paint.measureText("width")

        paint.textScaleX = 2f

        assertEquals(base * 2f, paint.measureText("width"), base / 100f)
    }

    @Test
    fun `reset restores the default scale`() {
        val paint = Paint()
        paint.textScaleX = 3f

        paint.reset()

        assertEquals(1f, paint.textScaleX, 0f)
    }

    @Test
    fun `the copy constructor carries the state over`() {
        // Rawkuma builds its stroke paint from the fill paint: Paint(fill).
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x11223344
            textSize = 17f
            textScaleX = 1.25f
            setTypeface(Typeface.MONOSPACE)
        }

        val stroke = Paint(fill)

        assertEquals(fill.color, stroke.color)
        assertEquals(fill.textSize, stroke.textSize, 0f)
        assertEquals(fill.textScaleX, stroke.textScaleX, 0f)
        assertEquals(fill.getTypeface(), stroke.getTypeface())
    }
}
