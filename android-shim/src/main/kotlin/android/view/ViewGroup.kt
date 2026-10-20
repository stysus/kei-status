package android.view

/** Stand-in for android.view.ViewGroup. */
open class ViewGroup : View() {

    private val children = mutableListOf<View>()

    open fun addView(child: View) {
        children += child
        child.attachTo(this)
    }

    open fun addView(child: View, params: LayoutParams) {
        child.layoutParams = params
        addView(child)
    }

    open fun removeView(view: View) {
        children -= view
        view.attachTo(null)
    }

    open fun removeAllViews() {
        children.forEach { it.attachTo(null) }
        children.clear()
    }

    open fun getChildCount(): Int = children.size

    open fun getChildAt(index: Int): View = children[index]

    /** Stand-in for android.view.ViewGroup.LayoutParams. */
    open class LayoutParams(
        @JvmField var width: Int = MATCH_PARENT,
        @JvmField var height: Int = MATCH_PARENT,
    ) {
        companion object {
            const val MATCH_PARENT: Int = -1
            const val WRAP_CONTENT: Int = -2
        }
    }

    /** Stand-in for android.view.ViewGroup.MarginLayoutParams. */
    class MarginLayoutParams(
        width: Int = MATCH_PARENT,
        height: Int = MATCH_PARENT,
        @JvmField var leftMargin: Int = 0,
        @JvmField var topMargin: Int = 0,
        @JvmField var rightMargin: Int = 0,
        @JvmField var bottomMargin: Int = 0,
    ) : LayoutParams(width, height)
}

/** Stand-in for android.view.MotionEvent: enough for sources that synthesise touch events. */
class MotionEvent {

    var action: Int = 0
    var x: Float = 0f
    var y: Float = 0f

    companion object {
        const val ACTION_DOWN: Int = 0
        const val ACTION_UP: Int = 1
        const val ACTION_MOVE: Int = 2
        const val ACTION_CANCEL: Int = 3

        @JvmStatic
        fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
            MotionEvent().apply {
                this.action = action
                this.x = x
                this.y = y
            }
    }
}
