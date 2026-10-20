package android.app

/**
 * Minimal stand-in for android.app.Activity.
 *
 * Nothing in the harness is ever shown, so an Activity is always usable: core's
 * `keiyoushi.utils.ui.usable()` checks these two flags before offering a dialog host.
 */
open class Activity {

    open val isFinishing: Boolean get() = false

    open val isDestroyed: Boolean get() = false

    open fun getApplication(): Application? = null
}
