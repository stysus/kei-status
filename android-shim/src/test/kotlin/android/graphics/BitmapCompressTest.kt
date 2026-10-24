package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Android's JPEG encoder takes any bitmap and drops the alpha; the JDK's one refuses the
 * alpha-backed types outright - `writer.write` on a `TYPE_INT_ARGB` image throws
 * `IIOException: Bogus input colorspace`. Rawkuma's text overlay decodes the site's alpha image and
 * re-encodes it as JPEG, so a shim that let the throw escape turned "image decoded fine" into
 * "image request failed".
 */
class BitmapCompressTest {

    @Test
    fun `an alpha bitmap compresses to jpeg`() {
        val source = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val bitmap = Bitmap(source, Bitmap.Config.ARGB_8888, true)
        val output = ByteArrayOutputStream()

        val wrote = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)

        assertTrue("compress returned false", wrote)
        val bytes = output.toByteArray()
        assertEquals("not a JPEG", 0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
    }

    @Test
    fun `the compressed jpeg decodes back opaque`() {
        val source = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        // A solid fill survives JPEG's DCT almost exactly; a lone pixel does not.
        for (y in 0 until 8) for (x in 0 until 8) source.setRGB(x, y, 0xFF336699.toInt())
        val bitmap = Bitmap(source, Bitmap.Config.ARGB_8888, true)
        val output = ByteArrayOutputStream()

        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)

        val decoded = ImageIO.read(ByteArrayInputStream(output.toByteArray()))
        // JPEG is lossy; the channels land near the originals and the alpha is gone.
        val rgb = decoded.getRGB(3, 3)
        assertEquals(0x33.toDouble(), ((rgb shr 16) and 0xff).toDouble(), 4.0)
        assertEquals(0x66.toDouble(), ((rgb shr 8) and 0xff).toDouble(), 4.0)
        assertEquals(0x99.toDouble(), (rgb and 0xff).toDouble(), 4.0)
    }

    @Test
    fun `an opaque rgb bitmap still compresses to jpeg`() {
        val source = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        source.setRGB(1, 1, 0x224466)
        val bitmap = Bitmap(source, Bitmap.Config.ARGB_8888, true)
        val output = ByteArrayOutputStream()

        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        assertTrue(output.size() > 0)
    }
}
