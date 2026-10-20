package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Exercises the paths extensions use to descramble page images: decode, tile-shuffle through
 * Canvas, re-encode, and decode the result again.
 */
class BitmapDescrambleTest {

    private val tileSize = 16

    private fun sourceImage(colors: IntArray): ByteArray {
        val image = BufferedImage(colors.size * tileSize, tileSize, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        colors.forEachIndexed { index, rgb ->
            g.color = java.awt.Color(rgb)
            g.fillRect(index * tileSize, 0, tileSize, tileSize)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("decode returned null", bitmap)
        return bitmap!!
    }

    private fun colorAt(bitmap: Bitmap, x: Int, y: Int): Int {
        val argb = bitmap.requireImage().getRGB(x, y)
        return argb and 0xFFFFFF
    }

    @Test
    fun `decodes png and reports dimensions`() {
        val bitmap = decode(sourceImage(intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)))

        assertEquals(tileSize * 4, bitmap.width)
        assertEquals(tileSize, bitmap.height)
    }

    @Test
    fun `tile shuffle places each source rect at its destination`() {
        val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00)
        val scrambled = decode(sourceImage(colors))
        val output = Bitmap.createBitmap(scrambled.width, scrambled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Reverse the tile order, the shape every descrambler reduces to.
        colors.indices.forEach { index ->
            val from = colors.size - 1 - index
            val src = Rect(from * tileSize, 0, (from + 1) * tileSize, tileSize)
            val dst = Rect(index * tileSize, 0, (index + 1) * tileSize, tileSize)
            canvas.drawBitmap(scrambled, src, dst, null)
        }

        colors.indices.forEach { index ->
            val from = colors.size - 1 - index
            assertEquals("tile $index", colors[from], colorAt(output, index * tileSize + 1, 1))
        }
    }

    @Test
    fun `matrix postScale and postTranslate mirror and place a tile`() {
        val fragment = decode(sourceImage(intArrayOf(0x00FF00)))
        val output = Bitmap.createBitmap(tileSize * 2, tileSize * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val matrix = Matrix().apply {
            postScale(-1f, 1f, fragment.width / 2f, fragment.height / 2f)
            postTranslate(tileSize.toFloat(), tileSize.toFloat())
        }
        canvas.drawBitmap(fragment, matrix, Paint())

        assertEquals(0x00FF00, colorAt(output, tileSize + 2, tileSize + 2))
        assertEquals(0x000000, colorAt(output, 2, 2))
    }

    @Test
    fun `compress to webp round trips through decodeStream`() {
        val bitmap = decode(sourceImage(intArrayOf(0xFF0000, 0x0000FF)))
        val encoded = ByteArrayOutputStream()
        assertTrue("webp encode failed", bitmap.compress(Bitmap.CompressFormat.WEBP, 100, encoded))

        val roundTripped = decode(encoded.toByteArray())
        assertEquals(bitmap.width, roundTripped.width)
        assertEquals(bitmap.height, roundTripped.height)
        // Lossy, so allow a small per-channel drift.
        val red = colorAt(roundTripped, 2, 2)
        val near = { actual: Int, expected: Int ->
            Math.abs((actual shr 16 and 0xFF) - (expected shr 16 and 0xFF)) <= 4 &&
                Math.abs((actual shr 8 and 0xFF) - (expected shr 8 and 0xFF)) <= 4 &&
                Math.abs((actual and 0xFF) - (expected and 0xFF)) <= 4
        }
        assertTrue("expected red near 0xFF0000, got ${red.toString(16)}", near(red, 0xFF0000))
        val blue = colorAt(roundTripped, bitmap.width - 3, 2)
        assertTrue("expected blue near 0x0000FF, got ${blue.toString(16)}", near(blue, 0x0000FF))
    }

    @Test
    fun `color matrix inverts drawn pixels`() {
        val white = decode(sourceImage(intArrayOf(0xFFFFFF)))
        val output = Bitmap.createBitmap(white.width, white.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(invert) }
        canvas.drawBitmap(white, null, RectF(0f, 0f, white.width.toFloat(), white.height.toFloat()), paint)

        assertEquals(0x000000, colorAt(output, 4, 4))
    }

    @Test
    fun `recycled bitmap cannot be drawn`() {
        val bitmap = decode(sourceImage(intArrayOf(0xFF0000)))
        bitmap.recycle()
        assertTrue(bitmap.isRecycled())
        try {
            bitmap.requireImage()
            throw AssertionError("expected recycled bitmap to be rejected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
