package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Stand-in for android.os.Looper.
 *
 * Only the main looper is pre-created, matching Android. [prepare]/[loop] are implemented for the
 * background-thread pattern some sources use (`Looper.prepare(); Handler().post { ... }; Looper.loop()`).
 */
class Looper private constructor(private val main: Boolean) {

    /** Tasks posted to this looper; the main looper's queue is drained by [MainThread]. */
    internal val queue = LinkedBlockingQueue<() -> Unit>()

    internal val scheduled = ConcurrentHashMap<Runnable, Future<*>>()

    @Volatile
    private var quitting = false

    internal fun enqueue(block: () -> Unit) {
        if (quitting) return
        if (main) {
            MainThread.executor.execute(block)
        } else {
            queue.put(block)
        }
    }

    internal fun enqueueDelayed(runnable: Runnable, delayMillis: Long): Boolean {
        if (quitting) return false
        // Register a placeholder before scheduling so the lambda's remove() always finds the
        // entry — even when delayMillis is 0 and the scheduler fires before this method returns.
        // Without it, the lambda's remove is a no-op and the real future (written after schedule)
        // stays in the map as a stale entry that leaks memory and misleads removeCallbacks.
        val placeholder = java.util.concurrent.CompletableFuture<Void>()
        scheduled[runnable] = placeholder
        val future = scheduler.schedule(
            {
                scheduled.remove(runnable)
                enqueue { runnable.run() }
            },
            delayMillis.coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        )
        // Atomically swap the placeholder for the real future only if the lambda has not already
        // removed it. If replace returns false the task already ran and cleaned up — do not
        // re-insert the spent future.
        scheduled.replace(runnable, placeholder, future)
        return true
    }

    internal fun cancel(runnable: Runnable) {
        scheduled.remove(runnable)?.cancel(false)
    }

    fun quit() {
        quitting = true
        scheduled.values.forEach { it.cancel(false) }
        scheduled.clear()
        if (!main) {
            queue.clear()
            // Wake a thread blocked in queue.take() inside loop() so it re-checks quitting and
            // exits, the same role Android's MSG_QUIT plays for MessageQueue.next().
            queue.put {}
        }
    }

    fun isCurrentThread(): Boolean =
        if (main) MainThread.isMainThread() else Thread.currentThread() === thread

    private var thread: Thread? = null

    internal fun bind(thread: Thread) {
        this.thread = thread
    }

    internal fun isMain(): Boolean = main

    companion object {
        private val mainLooper = Looper(main = true).also { it.bind(MainThread.thread ?: Thread.currentThread()) }

        private val threadLocal = ThreadLocal<Looper?>()

        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "kei-status-looper-timer").apply { isDaemon = true }
        }

        @JvmStatic
        fun getMainLooper(): Looper = mainLooper

        @JvmStatic
        fun myLooper(): Looper? = threadLocal.get() ?: if (MainThread.isMainThread()) mainLooper else null

        @JvmStatic
        fun prepare() {
            check(threadLocal.get() == null) { "Only one Looper may be created per thread" }
            threadLocal.set(Looper(main = false).also { it.bind(Thread.currentThread()) })
        }

        @JvmStatic
        fun loop() {
            val looper = checkNotNull(threadLocal.get()) { "No Looper; Looper.prepare() wasn't called on this thread." }
            check(!looper.main) { "The main looper is managed by the harness" }
            while (!looper.quitting) {
                val task = looper.queue.take()
                task()
            }
        }

        internal fun quitCurrent() {
            threadLocal.get()?.quit()
            threadLocal.remove()
        }
    }
}
