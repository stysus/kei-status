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
}
