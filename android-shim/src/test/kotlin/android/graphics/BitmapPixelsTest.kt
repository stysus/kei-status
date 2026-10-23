package android.graphics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Exercises the pixel-array surface SoraRaw's descrambler drives: read the page, permute the block
 * and the channels, then build a bitmap back from the array.
 */
class BitmapPixelsTest {

    private val pixels = intArrayOf(
        0xFF000000.toInt(), 0xFF000001.toInt(), 0xFF000002.toInt(),
        0xFF000010.toInt(), 0xFF000011.toInt(), 0xFF000012.toInt(),
    )

    @Test
    fun `createBitmap wraps an ARGB array and getPixels reads it back`() {
        val bitmap = Bitmap.createBitmap(pixels, 3, 2, Bitmap.Config.ARGB_8888)
        val readBack = IntArray(pixels.size)

        bitmap.getPixels(readBack, 0, 3, 0, 0, 3, 2)

        assertArrayEquals(pixels, readBack)
    }

    @Test
    fun `getPixels honours offset and stride`() {
        val bitmap = Bitmap.createBitmap(pixels, 3, 2, Bitmap.Config.ARGB_8888)
        val padded = IntArray(9)

        bitmap.getPixels(padded, 1, 4, 0, 0, 3, 2)

        assertArrayEquals(intArrayOf(0, 0xFF000000.toInt(), 0xFF000001.toInt(), 0xFF000002.toInt(), 0), padded.copyOfRange(0, 5))
        assertArrayEquals(intArrayOf(0xFF000010.toInt(), 0xFF000011.toInt(), 0xFF000012.toInt()), padded.copyOfRange(5, 8))
    }

    @Test
    fun `getPixels rejects a block that runs past the bitmap`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)

        assertThrows(IllegalArgumentException::class.java) {
            bitmap.getPixels(IntArray(9), 0, 3, 1, 0, 3, 2)
        }
    }

    @Test
    fun `getPixels rejects a stride narrower than the block`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)

        assertThrows(IllegalArgumentException::class.java) {
            bitmap.getPixels(IntArray(6), 0, 2, 0, 0, 3, 2)
        }
    }

    @Test
    fun `exactCentre is the float midpoint of the edges`() {
        val rect = Rect(2, 4, 8, 10)

        assertEquals(5f, rect.exactCenterX())
        assertEquals(7f, rect.exactCenterY())
    }

    @Test
    fun `createBitmap reads a strided array at its offset`() {
        val padded = intArrayOf(
            9, 0xFF000000.toInt(), 0xFF000001.toInt(), 9,
            9, 0xFF000010.toInt(), 0xFF000011.toInt(), 9,
            9, 0xFF000020.toInt(), 0xFF000021.toInt(), 9,
        )

        val bitmap = Bitmap.createBitmap(padded, 1, 4, 2, 3, Bitmap.Config.ARGB_8888)
        val readBack = IntArray(6)

        bitmap.getPixels(readBack, 0, 2, 0, 0, 2, 3)

        assertArrayEquals(
            intArrayOf(
                0xFF000000.toInt(), 0xFF000001.toInt(),
                0xFF000010.toInt(), 0xFF000011.toInt(),
                0xFF000020.toInt(), 0xFF000021.toInt(),
            ),
            readBack,
        )
    }

    @Test
    fun `createBitmap rejects a strided array that runs past the end`() {
        assertThrows(IllegalArgumentException::class.java) {
            Bitmap.createBitmap(IntArray(6), 1, 4, 2, 3, Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun `setPixels writes the array into the bitmap`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)

        bitmap.setPixels(pixels, 0, 3, 0, 0, 3, 2)

        assertArrayEquals(pixels, read(bitmap, 3, 2))
    }

    @Test
    fun `setPixels honours offset and stride`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val padded = intArrayOf(
            9, 0xFF000001.toInt(), 0xFF000002.toInt(), 9,
            9, 0xFF000011.toInt(), 0xFF000012.toInt(), 9,
        )

        bitmap.setPixels(padded, 1, 4, 0, 0, 2, 2)

        assertArrayEquals(
            intArrayOf(
                0xFF000001.toInt(), 0xFF000002.toInt(),
                0xFF000011.toInt(), 0xFF000012.toInt(),
            ),
            read(bitmap, 2, 2),
        )
    }

    @Test
    fun `setPixels writes a block in place and leaves the rest alone`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val block = intArrayOf(
            0xFF0000AA.toInt(), 0xFF0000BB.toInt(),
            0xFF0000CC.toInt(), 0xFF0000DD.toInt(),
        )

        bitmap.setPixels(block, 0, 2, 1, 2, 2, 2)

        assertArrayEquals(
            intArrayOf(
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0xFF0000AA.toInt(), 0xFF0000BB.toInt(), 0,
                0, 0xFF0000CC.toInt(), 0xFF0000DD.toInt(), 0,
            ),
            read(bitmap, 4, 4),
        )
    }

    @Test
    fun `setPixels reads a negative stride backwards`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // Row 0 lives at index 2 and row 1 at index 0.
        val backwards = intArrayOf(
            0xFF000011.toInt(), 0xFF000012.toInt(),
            0xFF000001.toInt(), 0xFF000002.toInt(),
        )

        bitmap.setPixels(backwards, 2, -2, 0, 0, 2, 2)

        assertArrayEquals(
            intArrayOf(
                0xFF000001.toInt(), 0xFF000002.toInt(),
                0xFF000011.toInt(), 0xFF000012.toInt(),
            ),
            read(bitmap, 2, 2),
        )
    }

    @Test
    fun `setPixels rejects a block that runs past the bitmap`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)

        assertThrows(IllegalArgumentException::class.java) {
            bitmap.setPixels(IntArray(9), 0, 3, 1, 0, 3, 2)
        }
    }

    @Test
    fun `setPixels rejects an immutable bitmap`() {
        val immutable = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false)

        assertThrows(IllegalStateException::class.java) {
            immutable.setPixels(IntArray(6), 0, 3, 0, 0, 3, 2)
        }
    }

    @Test
    fun `setPixels rejects a recycled bitmap`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.recycle()

        assertThrows(IllegalStateException::class.java) {
            bitmap.setPixels(IntArray(6), 0, 3, 0, 0, 3, 2)
        }
    }

    private fun read(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        bitmap.getPixels(out, 0, width, 0, 0, width, height)
        return out
    }
}
