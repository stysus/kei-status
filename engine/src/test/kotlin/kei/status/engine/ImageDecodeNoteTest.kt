package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note a downloaded image body earns, or null when it decoded and there is nothing to say.
 *
 * The header's content-type deliberately does not appear on a body that decoded: CDNs mislabel
 * images all the time (octet-stream for JPEG, e4p's rewritten WEBP keeping its old header), and a
 * note on a healthy body degraded fourteen perfectly working sources to WARN. The type still
 * travels inside the did-not-decode note, where it describes the bytes that failed.
 */
class ImageDecodeNoteTest {

    @Test
    fun `a decoded body earns no note`() {
        val png = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB).let { image ->
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "PNG", it) }.toByteArray()
        }

        assertNull(imageDecodeNote(0, png, "application/octet-stream"))
    }

    @Test
    fun `an empty body is empty regardless of type`() {
        assertEquals(
            "page[1] image body is empty",
            imageDecodeNote(1, ByteArray(0), "image/jpeg"),
        )
    }

    @Test
    fun `a body that cannot decode names the bytes not the header`() {
        val corrupt = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x01, 0x02)

        val note = imageDecodeNote(2, corrupt, "text/html")

        assertEquals("page[2] image bytes did not decode (5 bytes, 'text/html')", note)
    }

    @Test
    fun `an avif body is reported as undecodable-by-the-jvm`() {
        // ISO BMFF: size, 'ftyp', brand 'avif'.
        val avif = byteArrayOf(
            0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66, // ....ftypavif
            0, 0, 0, 0, 0x61, 0x76, 0x69, 0x66, 0x6d, 0x69, 0x66, 0x31,    // ....avifmif1
        )

        val note = imageDecodeNote(0, avif, "application/octet-stream")

        assertTrue(note!!.contains("image is AVIF"))
    }
}
