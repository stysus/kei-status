package kei.status.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `probeImages` reports "content-type is ..." when the bytes it received are not an image, so the
 * answer has to describe the bytes rather than the headers.
 *
 * The case that forced this: `:lib:e4p`'s interceptor replaces an `application/vnd.e4p.qsc` response
 * body with decrypted WEBP and leaves the server's header in place. Reading the header first made
 * every successfully decoded J-Novel page report a non-image type.
 */
class ContentTypeTest {

    @Test
    fun `the body's media type wins over a header the interceptor left behind`() {
        // Exactly what the e4p interceptor builds: same response, new bytes, old header.
        val server = response(header = QSC, body = bytes("not an image", QSC))
        val rewritten = server.newBuilder()
            .body(bytes("RIFF....WEBP", "image/webp"))
            .build()

        assertEquals(QSC, rewritten.header("Content-Type"))
        assertEquals("image/webp", contentTypeOf(rewritten))
    }

    @Test
    fun `a body without a media type falls back to the header`() {
        val built = response(header = "image/png", body = bytes("png bytes", null))

        assertEquals("image/png", contentTypeOf(built))
    }

    @Test
    fun `a plain response reports the type both sides agree on`() {
        val plain = response(header = "image/jpeg", body = bytes("jpeg", "image/jpeg"))

        assertEquals("image/jpeg", contentTypeOf(plain))
    }

    @Test
    fun `a response with neither reports an empty type`() {
        assertEquals("", contentTypeOf(response(header = null, body = bytes("bytes", null))))
    }

    @Test
    fun `an empty header is not mistaken for a type`() {
        assertEquals("", contentTypeOf(response(header = "", body = bytes("bytes", null))))
    }

    @Test
    fun `a body that is not an image still reports its own type`() {
        // The inverse of the e4p case: a header claiming an image does not launder non-image bytes.
        val lying = response(header = "image/png", body = bytes("<html>", "text/html"))

        assertEquals("text/html", contentTypeOf(lying))
    }

    /**
     * Bytes, not a `String`: okhttp's `String.toResponseBody` appends `charset=utf-8`, which the
     * interceptor never does - it hands over an okio `Buffer` with the media type it chose.
     */
    private fun bytes(body: String, type: String?): ResponseBody =
        body.toByteArray().toResponseBody(type?.toMediaType())

    private fun response(header: String?, body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/page").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .apply { if (header != null) header("Content-Type", header) }
        .body(body)
        .build()

    private companion object {
        const val QSC = "application/vnd.e4p.qsc"
    }
}
