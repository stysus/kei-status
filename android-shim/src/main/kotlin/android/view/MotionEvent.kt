package android.view

/**
 * Stand-in for android.view.MotionEvent, in the multi-pointer form sources use to synthesise
 * touches - `Comix` answers a captcha by swiping and tapping in the WebView.
 *
 * [android.webkit.WebView.dispatchTouchEvent] is the only consumer, and it turns these back into
 * browser input. The fields sources populate are plain public fields, as on Android, because they
 * are written with `putfield` rather than through setters.
 */
class MotionEvent private constructor(
    val downTime: Long,
    val eventTime: Long,
    val action: Int,
    private val properties: Array<PointerProperties>,
    private val coords: Array<PointerCoords>,
    val source: Int,
) {

    fun getPointerCount(): Int = coords.size

    fun getPointerId(index: Int): Int = properties[index].id

    fun getX(index: Int): Float = coords[index].x

    fun getY(index: Int): Float = coords[index].y

    fun getPressure(index: Int): Float = coords[index].pressure

    fun getTouchMajor(index: Int): Float = coords[index].touchMajor

    fun getTouchMinor(index: Int): Float = coords[index].touchMinor

    /** Android returns the instance to a pool; there is nothing to recycle here. */
    fun recycle() = Unit

    class PointerProperties {
        @JvmField var id: Int = 0
        @JvmField var toolType: Int = 0
    }

    class PointerCoords {
        @JvmField var x: Float = 0f
        @JvmField var y: Float = 0f
        @JvmField var pressure: Float = 1f
        @JvmField var size: Float = 1f
        @JvmField var touchMajor: Float = 0f
        @JvmField var touchMinor: Float = 0f
        @JvmField var toolMajor: Float = 0f
        @JvmField var toolMinor: Float = 0f
        @JvmField var orientation: Float = 0f
    }

    companion object {
        const val ACTION_DOWN: Int = 0
        const val ACTION_UP: Int = 1
        const val ACTION_MOVE: Int = 2
        const val ACTION_CANCEL: Int = 3
        const val TOOL_TYPE_UNKNOWN: Int = 0
        const val TOOL_TYPE_FINGER: Int = 1

        /**
         * Timestamps, meta state, precision, device id and flags are part of the platform signature
         * but never read: the browser keeps its own clock and only needs the points.
         */
        @JvmStatic
        fun obtain(
            downTime: Long,
            eventTime: Long,
            action: Int,
            pointerCount: Int,
            pointerProperties: Array<PointerProperties>,
            pointerCoords: Array<PointerCoords>,
            metaState: Int,
            buttonState: Int,
            xPrecision: Float,
            yPrecision: Float,
            deviceId: Int,
            edgeFlags: Int,
            source: Int,
            flags: Int,
        ): MotionEvent = MotionEvent(
            downTime,
            eventTime,
            action,
            Array(pointerCount) { index -> pointerProperties[index] },
            Array(pointerCount) { index -> pointerCoords[index] },
            source,
        )
    }
}
