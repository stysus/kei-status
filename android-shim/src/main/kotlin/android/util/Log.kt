package android.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes to the harness logger. Extension code uses this for diagnostics only, so a plain
 * stdout/stderr mapping is enough - no Android log buffer semantics are reproduced.
 *
 * A sweep runs every source in one JVM, so an extension that fails the same way for every language
 * variant would print the same line - and the same stack trace - a hundred times. Identical entries
 * are printed once and counted; the totals are printed when the JVM exits, so nothing is lost.
 */
object Log {

    const val VERBOSE: Int = 2
    const val DEBUG: Int = 3
    const val INFO: Int = 4
    const val WARN: Int = 5
    const val ERROR: Int = 6
    const val ASSERT: Int = 7

    @JvmStatic
    fun d(tag: String?, msg: String?): Int = print("D", tag, msg, null)

    @JvmStatic
    fun i(tag: String?, msg: String?): Int = print("I", tag, msg, null)

    @JvmStatic
    fun w(tag: String?, msg: String?): Int = print("W", tag, msg, null)

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = print("W", tag, msg, tr)

    @JvmStatic
    fun e(tag: String?, msg: String?): Int = print("E", tag, msg, null)

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = print("E", tag, msg, tr)

    @JvmStatic
    fun v(tag: String?, msg: String?): Int = print("V", tag, msg, null)

    @JvmStatic
    fun wtf(tag: String?, msg: String?): Int = print("A", tag, msg, null)

    @JvmStatic
    fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = print("A", tag, msg, tr)

    @JvmStatic
    fun wtf(tag: String?, tr: Throwable?): Int = print("A", tag, tr?.message, tr)

    /** Android's level-parameter form, used by core's WebView console logger. */
    @JvmStatic
    fun println(priority: Int, tag: String?, msg: String?): Int = print(levelOf(priority), tag, msg, null)

    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = true

    private fun levelOf(priority: Int): String = when (priority) {
        VERBOSE -> "V"
        DEBUG -> "D"
        INFO -> "I"
        WARN -> "W"
        ERROR -> "E"
        else -> "A"
    }

    private val entries = ConcurrentHashMap<String, AtomicInteger>()

    private fun print(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        val key = "$level\u0000${tag ?: "-"}\u0000${msg ?: ""}"
        val seen = entries.computeIfAbsent(key) { AtomicInteger() }.getAndIncrement()
        if (seen == 0) {
            System.err.println("[$level] ${tag ?: "-"}: ${msg ?: ""}")
            tr?.printStackTrace(System.err)
        }
        return 0
    }

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                entries.entries
                    .filter { it.value.get() > 1 }
                    .sortedByDescending { it.value.get() }
                    .forEach { (key, count) ->
                        val (level, tag, msg) = key.split('\u0000', limit = 3)
                        System.err.println("[${count.get()}x] [$level] $tag: $msg")
                    }
            },
        )
    }
}
