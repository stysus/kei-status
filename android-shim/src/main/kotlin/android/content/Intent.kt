package android.content

/**
 * Minimal stand-in for android.content.Intent.
 *
 * Only what extension code builds before handing the intent to [Context.startActivity] is provided,
 * and that call always refuses (see [Context.startActivity]): the harness has no foreground UI to
 * show an activity in. The members and signatures match the framework so bytecode compiled against
 * android.jar links cleanly.
 */
open class Intent {

    private var action: String? = null

    private var data: android.net.Uri? = null

    private var component: ComponentName? = null

    private var flags = 0

    private val extras = mutableMapOf<String, Any?>()

    constructor()

    constructor(component: ComponentName) {
        this.component = component
    }

    open fun getAction(): String? = action

    open fun setAction(action: String?): Intent = apply { this.action = action }

    open fun getData(): android.net.Uri? = data

    open fun setData(data: android.net.Uri?): Intent = apply { this.data = data }

    open fun getComponent(): ComponentName? = component

    open fun setComponent(component: ComponentName?): Intent = apply { this.component = component }

    open fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }

    open fun getFlags(): Int = flags

    open fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value }

    open fun putExtra(name: String, value: Long): Intent = apply { extras[name] = value }

    open fun putExtra(name: String, value: Int): Intent = apply { extras[name] = value }

    open fun putExtra(name: String, value: Boolean): Intent = apply { extras[name] = value }

    companion object {
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
