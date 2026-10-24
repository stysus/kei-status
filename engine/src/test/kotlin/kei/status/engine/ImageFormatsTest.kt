package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatsTest {

    private fun ftyp(brand: String): ByteArray = "....ftyp$brand....".toByteArray(Charsets.US_ASCII)

    @Test
    fun `an avif payload is named`() {
        assertEquals("AVIF", imageFormatName(ftyp("avif")))
        assertEquals("AVIF sequence", imageFormatName(ftyp("avis")))
    }

    @Test
    fun `a heif container is named by its brand`() {
        // Android decodes HEIF (HEIC photos included) natively; the JVM stack does not. The brand
        // is what the note reports, so the reader can tell the gap from real corruption. `heic`
        // is the brand still images ship as; `mif1`/`msf1` are the containers around it.
        assertEquals("HEIF (heic)", imageFormatName(ftyp("heic")))
        assertEquals("HEIF (mif1)", imageFormatName(ftyp("mif1")))
        assertEquals("HEIF (msf1)", imageFormatName(ftyp("msf1")))
    }

    @Test
    fun `a webp payload is not named`() {
        // webp-imageio is on the harness class path, so a WEBP payload decodes and never reaches
        // the failure branch; the checker must not claim it cannot decode one.
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        )
        assertNull(imageFormatName(webp))
    }

    @Test
    fun `anything else is not named`() {
        assertNull(imageFormatName(ByteArray(0)))
        assertNull(imageFormatName(ByteArray(12)))
        assertNull(imageFormatName("<html>hotlink refusal</html>.....".toByteArray(Charsets.US_ASCII)))
    }
}
