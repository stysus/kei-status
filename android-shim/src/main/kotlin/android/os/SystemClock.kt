package android.os

/** Minimal stand-in for android.os.SystemClock, used by rate limiters. */
object SystemClock {

    @JvmStatic
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000

    @JvmStatic
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
}
