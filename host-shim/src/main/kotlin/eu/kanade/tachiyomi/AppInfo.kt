package eu.kanade.tachiyomi

/** Host app identity exposed to extensions. */
@Suppress("UNUSED")
object AppInfo {

    fun getVersionCode(): Int = 1

    fun getVersionName(): String = "kei-status"
}
