package android.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.text.LineBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dragon Ball Multiverse and Manhuarm compose translated text onto page images through
 * `StaticLayout.Builder` - the builder and its dependencies have to link and lay out, or the
 * images scenario reports a missing platform class for a source that works on every device.
 */
class StaticLayoutBuilderTest {

    private val paint = TextPaint()

    @Test
    fun `obtain and build produce a working layout`() {
        val layout = StaticLayout.Builder.obtain("hello world", 0, "hello world".length, paint, 40)
            .build()

        assertTrue(layout.getLineCount() >= 2)
        assertEquals(layout.getLineCount() * lineHeight(40), layout.getHeight())
    }

    @Test
    fun `builder members the extensions call exist and return the builder`() {
        // Every call in DBM and Manhuarm, chained the way they chain it.
        val layout = StaticLayout.Builder.obtain("text", 0, 4, paint, 100)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
            .setIncludePad(false)
            .build()

        assertEquals(1, layout.getLineCount())
    }

    @Test
    fun `a built layout draws onto a canvas`() {
        val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layout = StaticLayout.Builder.obtain("draw me", 0, 7, paint, 60)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

        layout.draw(canvas)

        bitmap.recycle()
    }

    private fun lineHeight(width: Int): Int {
        val probe = StaticLayout("x", paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        return probe.getHeight()
    }
}
