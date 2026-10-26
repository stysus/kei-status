package android.os

import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
/**
 * The single thread that stands in for Android's main thread.
 *
 * Shared by [AndroidMainDispatcher] and [Looper] so that `Dispatchers.Main` and
 * `Looper.getMainLooper()` agree on which thread they are talking about - WebView callbacks depend
 * on that agreement to know whether they are already on the main thread.
 */
object MainThread {

    @Volatile
    var thread: Thread? = null
        private set

    /**
     * Nano-time when the task now running started, 0 when idle.
     *
     * One wedged callback would otherwise hold the single main thread for the rest of the
     * sweep: every later WebView callback queues behind it and times out, and even the
     * cookie pollers cannot reach their own exit check, which runs here.
     */
    private val taskStartedAt = AtomicLong(0)

    private val executor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kei-status-main").apply {
                isDaemon = true
                thread = this
            }
        }

    /** The only door onto the main thread; tracks how long the running task has held it. */
    fun post(block: () -> Unit) {
        executor.execute {
            taskStartedAt.set(System.nanoTime())
            try {
                block()
            } finally {
                taskStartedAt.set(0)
                Thread.interrupted()
            }
        }
    }

    fun isMainThread(): Boolean = Thread.currentThread() === thread

    init {
        // Materialise the thread eagerly: Looper.myLooper() has to be answerable before the first
        // task is dispatched.
        executor.execute { }
        startWatchdog()
    }

    /**
     * Interrupts the main thread when one task holds it far longer than any legitimate one.
     *
     * Main-thread work is short by construction — callbacks, result delivery, poller ticks —
     * so a generous bound only fires on a callback that blocks forever.  The interrupt gives
     * blocking primitives inside the wedged callback a way out, the backlog drains, and the
     * sweep continues — the same interrupt-and-move-on contract `withDeadline` gives scenario
     * threads.
     */
    private fun startWatchdog() {
        val watchdog = Thread({
            while (true) {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
                val running = taskStartedAt.get()
                if (running == 0L) continue
                val heldMs = (System.nanoTime() - running) / 1_000_000
                if (heldMs < WATCHDOG_LIMIT_MS) continue
                val wedged = thread ?: continue
                System.err.println(
                    "kei-status: main thread held ${heldMs / 1000}s by one task; interrupting",
                )
                wedged.interrupt()
            }
        }, "kei-status-main-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    private const val WATCHDOG_INTERVAL_MS = 10_000L
    private const val WATCHDOG_LIMIT_MS = 120_000L
}

/**
 * Supplies `Dispatchers.Main` on the JVM.
 *
 * On Android that dispatcher comes from `kotlinx-coroutines-android`; `core/`'s WebView helpers
 * hop to it with `withContext(Dispatchers.Main)`. Without a factory, `Dispatchers.Main` throws
 * "Module with the Main dispatcher is missing" before the real (missing WebView) problem is
 * reached, which would misreport a source as broken.
 *
 * Registered through kotlinx-coroutines' own ServiceLoader hook - the same mechanism the Android
 * artifact uses - so nothing is patched or reflected into. The SPI is annotated `@InternalCoroutinesApi`,
 * which is exactly the opt-in this class performs.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
class AndroidMainDispatcherFactory : MainDispatcherFactory {

    override val loadPriority: Int = 0

    override fun createDispatcher(
        allFactories: List<MainDispatcherFactory>,
    ): MainCoroutineDispatcher = AndroidMainDispatcher

    override fun hintOnError(): String =
        "kei-status provides a single-threaded Main dispatcher"
}

/**
 * Stands in for the Android main looper: one daemon thread, FIFO.
 */
object AndroidMainDispatcher : MainCoroutineDispatcher() {

    override val immediate: MainCoroutineDispatcher get() = this

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        MainThread.post(block::run)
    }
}
