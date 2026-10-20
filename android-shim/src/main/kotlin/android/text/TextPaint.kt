package android.text

import android.graphics.Paint

/** Stand-in for android.text.TextPaint. */
open class TextPaint(flags: Int = 0) : Paint(flags) {
    var baselineShift: Float = 0f
    var bgColor: Int = 0
    var linkColor: Int = 0
}
