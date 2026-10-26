package kei.status.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summary line is a machine contract: `ci/run-shard.sh` greps `^0 module(s) checked` to
 * reject a shard that checked nothing, and that grep is the only thing that prevents an empty
 * sweep from being published as a success.
 */
class SummaryFormatTest {

    @Test
    fun `an empty run produces the summary line run-shard greps for`() {
        val line = summaryLine(checked = 0, changed = 0, transitions = 0)
        assertTrue(
            "summary line must start with '0 module(s) checked' for run-shard.sh, got: $line",
            line.matches(Regex("^0 module\\(s\\) checked.*")),
        )
    }

    @Test
    fun `a non-empty run does not match the empty-run pattern`() {
        val line = summaryLine(checked = 1, changed = 0, transitions = 0)
        assertFalse(
            "a run that checked modules must not match the empty-run pattern, got: $line",
            line.matches(Regex("^0 module\\(s\\) checked.*")),
        )
    }

    @Test
    fun `double-digit counts do not false-match`() {
        val line = summaryLine(checked = 10, changed = 3, transitions = 5)
        assertFalse(
            "10 modules must not match '0 module(s) checked', got: $line",
            line.matches(Regex("^0 module\\(s\\) checked.*")),
        )
    }
}
