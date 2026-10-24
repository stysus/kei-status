package android.graphics.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The break-strategy constants DBM and Manhuarm pass to `StaticLayout.Builder`. The framework's
 * values are fixed, and extensions compare against them, so the shim has to carry the same ints.
 */
class LineBreakerTest {

    @Test
    fun `break strategy constants match the framework`() {
        assertEquals(0, LineBreaker.BREAK_STRATEGY_SIMPLE)
        assertEquals(1, LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        assertEquals(2, LineBreaker.BREAK_STRATEGY_BALANCED)
    }
}
