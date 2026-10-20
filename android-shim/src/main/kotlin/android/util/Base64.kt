package android.util

import java.util.Base64 as JBase64

/** Matches android.util.Base64 semantics for the flags extensions use. */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if (flags and URL_SAFE != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        if (flags and NO_PADDING != 0) encoder.withoutPadding()
        return encoder.encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val normalized = if (flags and URL_SAFE != 0) {
            str.replace('-', '+').replace('_', '/')
        } else {
            str
        }
        return JBase64.getMimeDecoder().decode(normalized)
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray()
}
