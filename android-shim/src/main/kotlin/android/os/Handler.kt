package android.os

/**
 * Stand-in for android.os.Handler.
 *
 * Sources use it to defer work onto the main thread - most notably `runWebViewBlocking` callers
 * that must not block it, and sources that poll a page after a delay.
 */
open class Handler(private val looper: Looper? = Looper.myLooper()) {

    private val target: Looper = looper
        ?: throw IllegalStateException("Can't create handler inside thread that has not called Looper.prepare()")

    fun getLooper(): Looper = target

    fun post(runnable: Runnable): Boolean {
        target.enqueue { runnable.run() }
        return true
    }

    fun postAtTime(runnable: Runnable, uptimeMillis: Long): Boolean {
        val delay = uptimeMillis - SystemClock.uptimeMillis()
        return target.enqueueDelayed(runnable, delay)
    }

    fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean =
        target.enqueueDelayed(runnable, delayMillis)

    fun removeCallbacks(runnable: Runnable) {
        target.cancel(runnable)
    }

    fun removeCallbacksAndMessages(token: Any?) {
        // Callers in the extension tree pass null, meaning "everything".
        if (token == null) target.scheduled.keys.forEach { target.cancel(it) }
    }
}
