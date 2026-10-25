package kei.status.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a [withDeadline] call produced. */
internal sealed interface DeadlineOutcome<out T> {
    data class Value<T>(val value: T) : DeadlineOutcome<T>

    data class Failed(val error: Throwable) : DeadlineOutcome<Nothing>

    data object Expired : DeadlineOutcome<Nothing>
}

/**
 * Cached thread pool for scenario threads.
 *
 * A new thread per scenario spends 0.1–0.5ms in OS thread creation, which adds up across
 * hundreds of scenarios in a shard. A cached pool reuses threads that finished their previous
 * scenario and are still warm, so only the first handful of calls ever create a thread. Threads
 * are daemon so they cannot hold the JVM open, matching the previous behaviour.
 */
private val scenarioPool: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "kei-status-scenario").also { it.isDaemon = true }
}

/**
 * Runs [block] and gives up after [timeout], whether or not [block] is willing to stop.
 *
 * `withTimeout` alone is not enough here. It cancels at the next suspension point, and extension
 * code is free to block: a source may call OkHttp's `execute()`, wait on a monitor, or sleep. A
 * blocking call therefore runs to its own end - measured at 3.0s under a 0.3s `withTimeout`, which
 * did not even report a timeout - and one module in a sweep held a shard for 27 minutes that way,
 * parked in `Http2Stream.waitForIo`.
 *
 * So the block gets a thread of its own and the deadline is enforced from outside it. On expiry the
 * thread is interrupted, which reaches both shapes: `Object.wait` and `Thread.sleep` throw, and
 * `runBlocking` turns an interrupt into coroutine cancellation. A block that ignores the interrupt
 * is abandoned rather than waited for; the thread is a daemon so it cannot hold the JVM open, and
 * the report moves on instead of losing the whole sweep to one source.
 *
 * The block's own outcome travels back as a value rather than as a thrown exception, because
 * coroutines copy an exception that crosses a boundary (stack-trace recovery), which would hide the
 * original one behind a second wrapper.
 */
internal suspend fun <T> withDeadline(timeout: Duration, block: suspend () -> T): DeadlineOutcome<T> {
    val result = CompletableFuture<DeadlineOutcome<T>>()
    val future = scenarioPool.submit {
        result.complete(
            try {
                DeadlineOutcome.Value(runBlocking { block() })
            } catch (t: Throwable) {
                DeadlineOutcome.Failed(t)
            },
        )
    }

    try {
        return runInterruptible { result.get(timeout.toMillis(), TimeUnit.MILLISECONDS) }
    } catch (e: TimeoutException) {
        future.cancel(true)
        return DeadlineOutcome.Expired
    } catch (e: CancellationException) {
        // The caller was cancelled - a shard shutting down, say. Nothing is waiting for this
        // answer, so stop the work instead of leaving it running behind a dead run.
        future.cancel(true)
        throw e
    } finally {
        if (!result.isDone) future.cancel(true)
    }
}
