package android.os

import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
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

    val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kei-status-main").apply {
            isDaemon = true
            thread = this
        }
    }

    init {
        // Materialise the thread eagerly: Looper.myLooper() has to be answerable before the first
        // task is dispatched.
        executor.execute { }
    }

    fun isMainThread(): Boolean = Thread.currentThread() === thread
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
        MainThread.executor.execute(block)
    }
}
