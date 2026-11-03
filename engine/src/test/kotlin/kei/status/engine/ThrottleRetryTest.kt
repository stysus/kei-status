package kei.status.engine

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/**
 * The 429 retry: one wait after the first 429, a longer one after the second, then the throttle
 * is real and surfaces as `THROTTLED`. `Retry-After = 0` keeps the test instant — the production
 * backoffs live in `throttleBackoffMs`, not in the attempt count these pins hold.
 */
class ThrottleRetryTest {

    private val runner = HarnessRunner(
        HarnessConfig(
            sourceRoot = Path.of("extensions-source"),
            module = ":src:en:example",
            scratchRoot = Path.of("build/scratch"),
        ),
    )

    @Test
    fun `a 429 that clears on the third attempt is not a throttle`() = runBlocking {
        var attempts = 0

        val captured = runner.capture<String>("popular") {
            if (++attempts < 3) throw HttpException(429, retryAfterSeconds = 0)
            "recovered"
        }

        assertEquals(3, attempts)
        assertEquals(Status.PASS, captured.result.status)
    }

    @Test
    fun `two retries are the limit, then the throttle is reported`() = runBlocking {
        var attempts = 0

        val captured = runner.capture<String>("popular") {
            attempts++
            throw HttpException(429, retryAfterSeconds = 0)
        }

        assertEquals(3, attempts)
        assertEquals(Status.THROTTLED, captured.result.status)
    }

    @Test
    fun `a non-429 exception is not retried`() = runBlocking {
        var attempts = 0

        val captured = runner.capture<String>("popular") {
            attempts++
            throw HttpException(403)
        }

        assertEquals(1, attempts)
        assertEquals(Status.BLOCKED, captured.result.status)
    }

    @Test
    fun `the backoff ladder prefers Retry-After, then waits longer on the second retry, capped`() {
        // The exact ladder: a site's own Retry-After wins and is capped at 10s; without one the
        // first wait is 1s and the second is 5s, because attempt+1s+attempt failed twice in a row
        // in the sweep data - the throttle window outlasted the first backoff.
        assertEquals(0L, throttleBackoffMs(retryAfterSeconds = 0, attempt = 1))
        assertEquals(3_000L, throttleBackoffMs(retryAfterSeconds = 3, attempt = 1))
        assertEquals(10_000L, throttleBackoffMs(retryAfterSeconds = 3_600, attempt = 1))
        assertEquals(1_000L, throttleBackoffMs(retryAfterSeconds = null, attempt = 1))
        assertEquals(5_000L, throttleBackoffMs(retryAfterSeconds = null, attempt = 2))
    }
}
