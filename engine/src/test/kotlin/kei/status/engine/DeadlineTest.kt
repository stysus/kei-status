package kei.status.engine

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeadlineTest {

    @Test
    fun `a value comes back unchanged`() = runBlocking {
        val outcome = withDeadline(Duration.ofSeconds(5)) { "pages" }

        assertEquals(DeadlineOutcome.Value("pages"), outcome)
    }

    @Test
    fun `a suspending wait inside the block is honoured, not treated as a stall`() = runBlocking {
        // The 429 retry waits inside the scenario, on the thread `withDeadline` owns. If a delay
        // there were cut short or reported as expiry, the retry would ask again immediately and the
        // backoff would silently do nothing.
        val start = System.nanoTime()

        val outcome = withDeadline(Duration.ofSeconds(5)) {
            kotlinx.coroutines.delay(300)
            "asked again"
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(DeadlineOutcome.Value("asked again"), outcome)
        assertTrue("the wait should have been 300ms, took ${elapsedMs}ms", elapsedMs >= 300)
    }

    @Test
    fun `a failure is reported as itself, not as a timeout`() = runBlocking {
        val outcome = withDeadline(Duration.ofSeconds(5)) { throw IllegalStateException("HTTP 404") }

        val error = (outcome as DeadlineOutcome.Failed).error
        assertEquals("HTTP 404", error.message)
    }

    @Test
    fun `a blocking call is stopped at the deadline`() = runBlocking {
        // The bug this guards: `withTimeout` around `Thread.sleep(3s)` with a 0.3s budget took the
        // full 3s and did not even report a timeout, because a blocking call never reaches a
        // suspension point. An extension parked in OkHttp's `execute()` behaves the same way.
        val started = System.nanoTime()

        val outcome = withDeadline(Duration.ofMillis(300)) {
            Thread.sleep(60_000)
            "never reached"
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(DeadlineOutcome.Expired, outcome)
        assertTrue("returned after ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `a suspending call that never resumes is stopped at the deadline`() = runBlocking {
        val started = System.nanoTime()

        val outcome = withDeadline(Duration.ofMillis(300)) { awaitCancellation() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(DeadlineOutcome.Expired, outcome)
        assertTrue("returned after ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `a block that ignores the interrupt does not hold the caller`() = runBlocking {
        // A block can swallow the interrupt. The run must still move on: this is the difference
        // between one slow source and a shard that never finishes.
        val release = CountDownLatch(1)
        val started = System.nanoTime()

        val outcome = withDeadline(Duration.ofMillis(300)) {
            while (release.count > 0) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // deliberately ignored, the way a third-party library might
                }
            }
            "eventually"
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        release.countDown()
        assertEquals(DeadlineOutcome.Expired, outcome)
        assertTrue("returned after ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
