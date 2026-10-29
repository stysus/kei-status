package eu.kanade.tachiyomi.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
class ThrowIfThrottledTest {
    private fun response(code: Int, retryAfter: String? = null): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .apply { retryAfter?.let { header("Retry-After", it) } }
        .body("x".toByteArray().let { okhttp3.ResponseBody.create("text/plain".toMediaType(), it) })
        .build()

    @Test
    fun `a 429 throws with its retry-after parsed as delta seconds`() {
        try {
            response(429, retryAfter = " 7 ").throwIfThrottled()
            fail("no throw")
        } catch (e: HttpException) {
            assertEquals(429, e.code)
            assertEquals(7L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `a 429 without a parseable retry-after carries null`() {
        listOf(null, "Wed, 21 Oct 2026 07:28:00 GMT", "soon").forEach { header ->
            try {
                response(429, retryAfter = header).throwIfThrottled()
                fail("no throw for header=$header")
            } catch (e: HttpException) {
                assertEquals(429, e.code)
                assertEquals(null, e.retryAfterSeconds)
            }
        }
    }

    @Test
    fun `any other status passes through untouched`() {
        // Other codes are recorded by the probes as notes; only a throttle is a verdict.
        listOf(200, 403, 404, 503).forEach { code ->
            response(code).throwIfThrottled()
        }
    }
}
