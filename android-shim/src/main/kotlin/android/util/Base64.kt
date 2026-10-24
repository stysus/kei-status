package android.util

import java.util.Base64 as JBase64

/**
 * Matches android.util.Base64 semantics for the flags extensions use.
 *
 * Both the `String` and the `byte[]` overloads exist because extensions call both: the Arabic and
 * Chinese CryptoUtils hand AES material over as raw UTF-8 bytes, while ebookjapan passes a String.
 * A shim that only offers one turns the other into a `NoSuchMethodError` at link time.
 *
 * One documented deviation: the JVM encoders never line-wrap, so `DEFAULT` (which Android wraps at
 * 76 chars) produces the same single line as `NO_WRAP`, and `CRLF` is inert. Nothing observed sends
 * an encode result that depends on the newlines, and emitting them would alter payloads sites
 * already accept. Decoding goes through the MIME decoder, which accepts wrapped input either way.
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if (flags and URL_SAFE != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        // withoutPadding() returns a NEW encoder; calling it as a statement would drop it and
        // leak '=' into the output. Signature headers and URL tokens (yaoime, dilar, mangafire,
        // lunaranime, hikarinagi, ebookjapan, cubari) are checked byte for byte, so the flag
        // changing the output is not optional.
        val exact = if (flags and NO_PADDING != 0) encoder.withoutPadding() else encoder
        return exact.encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray =
        JBase64.getMimeDecoder().decode(normalize(str.toByteArray(Charsets.US_ASCII), flags))

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray =
        JBase64.getMimeDecoder().decode(normalize(input, flags))

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray()

    /** The URL-safe alphabet differs in two characters; the MIME decoder only knows the standard one. */
    private fun normalize(input: ByteArray, flags: Int): ByteArray {
        if (flags and URL_SAFE == 0) return input

        val translated = ByteArray(input.size)
        for (index in input.indices) {
            translated[index] = when (input[index].toInt().toChar()) {
                '-' -> '+'.code.toByte()
                '_' -> '/'.code.toByte()
                else -> input[index]
            }
        }
        return translated
    }
}
