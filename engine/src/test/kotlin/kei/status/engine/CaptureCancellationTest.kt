package kei.status.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * A `CancellationException` thrown by extension code inside a scenario is that scenario's failure,
 * not the run being torn down.
 *
 * The scenario block runs on its own thread under its own `runBlocking`, so its cancellation is
 * isolated from the run's. Tear-down arrives on the caller side and `withDeadline` rethrows it
 * there without it ever becoming a `Failed` outcome. A `Failed` cancellation - an extension's own
 * `withTimeout`, say, whose `TimeoutCancellationException` is a `CancellationException` - used to
 * be rethrown instead of reported, which killed the whole module and silently dropped every other
 * source in it.
 */
class CaptureCancellationTest {

    private val runner = HarnessRunner(
        HarnessConfig(
            sourceRoot = Path.of("extensions-source"),
            module = ":src:en:example",
            scratchRoot = Path.of("build/scratch"),
        ),
    )

    @Test
    fun `a cancellation from the extension is reported, not rethrown`() = runBlocking {
        val captured = runner.capture<String>("popular") {
            throw CancellationException("extension's own withTimeout fired")
        }

        assertEquals(Status.FAIL, captured.result.status)
        assertEquals("extension's own withTimeout fired", captured.result.message)
    }
}
