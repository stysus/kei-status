package android.content

import java.io.File
import java.util.Properties

/**
 * Properties-file backed SharedPreferences.
 *
 * Not a faithful port of Android's XML format: the harness only needs values to survive within a
 * run so preference-dependent sources behave consistently.
 */
class FileSharedPreferences(private val file: File) : SharedPreferences {

    private val values = Properties().apply {
        if (file.isFile) file.inputStream().use { load(it) }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        file.outputStream().use { values.store(it, null) }
    }

    override fun getAll(): Map<String, *> = values.entries.associate { it.key.toString() to it.value }

    override fun getString(key: String, defValue: String?): String? =
        values.getProperty(key) ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values.getProperty(key)?.split('\u0000')?.toSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        values.getProperty(key)?.toIntOrNull() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        values.getProperty(key)?.toLongOrNull() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values.getProperty(key)?.toFloatOrNull() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values.getProperty(key)?.toBooleanStrictOrNull() ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply {
            pending[key] = values?.joinToString("\u0000")
        }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value.toString() }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearRequested = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) values.clear()
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values.setProperty(key, value)
            }
            persist()
        }
    }
}
