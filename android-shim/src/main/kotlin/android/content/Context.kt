package android.content

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

    open fun getCacheDir(): File = root().resolve("cache").apply { mkdirs() }

    open fun getFilesDir(): File = root().resolve("files").apply { mkdirs() }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        sharedPrefs.getOrPut(name) {
            FileSharedPreferences(root().resolve("shared_prefs/$name.xml"))
        }

    open fun getString(resId: Int): String = "string-$resId"

    private val sharedPrefs = mutableMapOf<String, SharedPreferences>()
}
