package android.app

import android.content.ContextThemeWrapper

/**
 * Minimal stand-in for android.app.Activity.
 *
 * Nothing in the harness is ever shown, so an Activity is always usable: core's
 * `keiyoushi.utils.ui.usable()` checks these two flags before offering a dialog host.
 *
 * It extends [ContextThemeWrapper] because the platform one does. Core code that merges an
 * `Activity` with the application context resolves to `ContextWrapper` at compile time, and the
 * JVM needs that link to exist to load the call at all.
 */
open class Activity : ContextThemeWrapper() {

    open val isFinishing: Boolean get() = false

    open val isDestroyed: Boolean get() = false

    open fun getApplication(): Application? = null
}
