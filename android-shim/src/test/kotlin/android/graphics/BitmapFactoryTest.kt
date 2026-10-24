package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Android's decode contract: failure is a null return, never a throw. ImageIO underneath does throw
 * on bodies it recognises but cannot parse - a corrupt JPEG lands as `IIOException: Bogus ...` - and
 * a shim that let that escape reported a decode failure as "image request failed", hiding both the
 * network success and the format note the null path writes.
 */
class BitmapFactoryTest {

    @Test
    fun `a plain png decodes`() {
        val image = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { buffer ->
            javax.imageio.ImageIO.write(image, "PNG", buffer)
        }.toByteArray()

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertNotNull(bitmap)
        bitmap!!.recycle()
    }

    @Test
    fun `a body imageio throws on reads back null`() {
        val corruptJpeg = corruptJpeg()

        assertNull(BitmapFactory.decodeByteArray(corruptJpeg, 0, corruptJpeg.size))
    }

    @Test
    fun `empty input reads back null`() {
        assertNull(BitmapFactory.decodeByteArray(ByteArray(0), 0, 0))
    }

    /**
     * SOI, JFIF APP0, one DQT, an SOF0 with three components, a truncated scan - enough structure
     * for the JPEG reader to commit and then throw while parsing.
     */
    private fun corruptJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        out.write(byteArrayOf(0xff.toByte(), 0xe0.toByte(), 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        out.write(byteArrayOf(0xff.toByte(), 0xdb.toByte(), 0, 67, 0) + ByteArray(64) { 1 })
        out.write(byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0, 17, 8, 0, 8, 0, 8, 3))
        out.write(byteArrayOf(0x41, 0x00, 0x01, 0x42, 0x00, 0x01, 0x43, 0x00, 0x01))
        out.write(byteArrayOf(0xff.toByte(), 0xc4.toByte(), 0, 19, 0) + ByteArray(17) { 1 })
        out.write(byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 12, 3, 0x41, 0, 0x42, 0, 0x43, 0, 0, 63, 0))
        out.write(ByteArray(8))
        out.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        return out.toByteArray()
    }
}
