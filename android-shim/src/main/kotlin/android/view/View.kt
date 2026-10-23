package android.view

import android.content.res.Resources
import android.os.Handler
import android.os.Looper

/** Stand-in for android.view.View. Only what extensions and WebView plumbing touch. */
open class View {

    @JvmField
    internal var context: android.content.Context? = null

    open var layoutParams: ViewGroup.LayoutParams? = null

    private var parentView: ViewGroup? = null

    private var measuredWidth = 0
    private var measuredHeight = 0

    private var width = 0
    private var height = 0

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    open fun getParent(): ViewParent? = parentView

    /** Android resolves this through the view's context; sources read `displayMetrics` off it. */
    open fun getResources(): Resources = context?.getResources() ?: Resources.getSystem()

    internal fun attachTo(parent: ViewGroup?) {
        parentView = parent
    }

    open fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
    }

    open fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        width = right - left
        height = bottom - top
    }

    open fun getMeasuredWidth(): Int = measuredWidth

    open fun getMeasuredHeight(): Int = measuredHeight

    open fun getWidth(): Int = width

    open fun getHeight(): Int = height

    open fun post(action: Runnable): Boolean = handler.post(action)

    open fun postDelayed(action: Runnable, delayMillis: Long): Boolean = handler.postDelayed(action, delayMillis)

    open fun postOnAnimation(action: Runnable) {
        handler.postDelayed(action, 16)
    }

    open fun postOnAnimationDelayed(action: Runnable, delayMillis: Long) {
        handler.postDelayed(action, delayMillis + 16)
    }

    /** Android answers `true` for every action, including null, which it silently ignores. */
    open fun removeCallbacks(action: Runnable?): Boolean {
        if (action != null) handler.removeCallbacks(action)
        return true
    }

    open fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    open fun setBackgroundColor(color: Int) = Unit

    open fun setVisibility(visibility: Int) = Unit

    open fun getVisibility(): Int = VISIBLE

    /** Stand-in for android.view.View.MeasureSpec. */
    object MeasureSpec {

        private const val MODE_SHIFT = 30
        private const val MODE_MASK = 0x3 shl MODE_SHIFT

        const val UNSPECIFIED: Int = 0
        const val EXACTLY: Int = 1 shl MODE_SHIFT
        const val AT_MOST: Int = 2 shl MODE_SHIFT

        @JvmStatic
        fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)

        @JvmStatic
        fun getMode(measureSpec: Int): Int = measureSpec and MODE_MASK

        @JvmStatic
        fun getSize(measureSpec: Int): Int = measureSpec and MODE_MASK.inv()
    }

    companion object {
        const val VISIBLE: Int = 0
        const val INVISIBLE: Int = 4
        const val GONE: Int = 8
    }
}
