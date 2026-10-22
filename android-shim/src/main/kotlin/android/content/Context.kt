package android.content

import android.content.res.Resources
import java.io.File

/**
 * Minimal stand-in for android.content.Context.
 *
 * Only the members extension code actually reaches are implemented. Signatures must match the
 * real framework so bytecode compiled against android.jar links cleanly.
 */
open class Context {

    private var root: File? = null

    /** Used by the harness to point the context at a per-source scratch directory. */
    fun attachRoot(dir: File) {
        root = dir
    }

    private fun root(): File = root ?: error("Context not attached")

    open fun getResources(): Resources = Resources.getSystem()

    open fun getApplicationContext(): Context = this

    open fun getCacheDir(): File = root().resolve("cache").apply { mkdirs() }

    open fun getFilesDir(): File = root().resolve("files").apply { mkdirs() }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        sharedPrefs.getOrPut(name) {
            FileSharedPreferences(root().resolve("shared_prefs/$name.xml"))
        }

    open fun getString(resId: Int): String = "string-$resId"

    /**
     * The harness runs every extension in one JVM, so there is no per-extension package. The value
     * only ever names the activity an [Intent] targets, and nothing launches it.
     */
    open fun getPackageName(): String = "kei.status.harness"

    /**
     * Refuses every intent: there is no foreground UI to show an activity in.
     *
     * Throwing rather than ignoring is deliberate. It is what a device without the target activity
     * does, and extensions that need a human (a captcha in a WebView) catch it and report their own
     * message; silently dropping the intent would leave them polling for a change that can never
     * happen.
     */
    open fun startActivity(intent: Intent) {
        val target = intent.getComponent()?.toString() ?: intent.getAction() ?: "intent"
        throw ActivityNotFoundException("no activity to handle $target")
    }

    private val sharedPrefs = mutableMapOf<String, SharedPreferences>()
}
