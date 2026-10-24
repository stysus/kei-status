package android.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Extensions hand these flags to real APIs - signature headers (yaoime, dilar, mangafire,
 * lunaranime), URL tokens (hikarinagi, ebookjapan) and path ids (cubari) - so the output has to
 * match what the same flags produce on Android, byte for byte.
 */
class Base64Test {

    @Test
    fun `no padding strips the pad characters`() {
        // android.util.Base64 with NO_PADDING emits no '='. java.util's withoutPadding() returns a
        // NEW encoder; calling it as a statement drops the result and keeps the padding.
        val encoded = Base64.encodeToString("any carnal pleasure".toByteArray(), Base64.NO_PADDING)

        assertFalse("padding leaked into NO_PADDING output: $encoded", encoded.contains('='))
    }

    @Test
    fun `url safe with no padding and no wrap is the base64url alphabet`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte()) // "-_8" in the url-safe alphabet
        val encoded = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        assertEquals("-_8", encoded)
    }

    @Test
    fun `round trip through url safe survives the alphabet translation`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xc0.toByte(), 0xab.toByte())
        val encoded = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        assertArrayEquals(bytes, Base64.decode(encoded, Base64.URL_SAFE))
    }

    @Test
    fun `no wrap and default produce a single line`() {
        // Documented deviation, not a bug: this encoder never line-wraps, so DEFAULT and NO_WRAP
        // agree. Android's DEFAULT wraps at 76 chars; nothing observed depends on that, and
        // emitting the newlines would change payloads that sites already accept.
        val long = "0123456789abcdef".repeat(10).toByteArray()

        val default = Base64.encodeToString(long, Base64.DEFAULT)
        val noWrap = Base64.encodeToString(long, Base64.NO_WRAP)

        assertEquals(noWrap, default)
    }
}
