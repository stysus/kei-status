package android.util

/**
 * Routes to the harness logger. Extension code uses this for diagnostics only, so a plain
 * stdout/stderr mapping is enough - no Android log buffer semantics are reproduced.
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

    private fun print(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("[$level] ${tag ?: "-"}: ${msg ?: ""}")
        tr?.printStackTrace(System.err)
        return 0
    }
}
