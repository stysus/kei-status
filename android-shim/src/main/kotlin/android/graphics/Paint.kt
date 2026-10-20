package android.graphics

/** Stand-in for android.graphics.Paint. Only the state Canvas reads is modelled. */
open class Paint {

    var color: Int = Color.BLACK
        set(value) {
            field = value
            alpha = Color.alpha(value)
        }

    var alpha: Int = 255

    var style: Style = Style.FILL

    var strokeWidth: Float = 0f

    var isAntiAlias: Boolean = false

    var isFilterBitmap: Boolean = true

    var isDither: Boolean = false

    var colorFilter: ColorFilter? = null

    var textSize: Float = 12f

    var typeface: Typeface? = Typeface.DEFAULT

    var strokeCap: Cap = Cap.BUTT

    var strokeJoin: Join = Join.MITER

    constructor()

    constructor(flags: Int) {
        isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
        isFilterBitmap = flags and FILTER_BITMAP_FLAG != 0
        isDither = flags and DITHER_FLAG != 0
    }

    fun reset() {
        color = Color.BLACK
        alpha = 255
        style = Style.FILL
        strokeWidth = 0f
        isAntiAlias = false
        isFilterBitmap = true
        isDither = false
        colorFilter = null
        textSize = 12f
        typeface = Typeface.DEFAULT
    }

    fun setARGB(a: Int, r: Int, g: Int, b: Int) {
        color = Color.argb(a, r, g, b)
    }

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) = Unit

    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    enum class Cap { BUTT, ROUND, SQUARE }

    enum class Join { MITER, ROUND, BEVEL }

    companion object {
        const val ANTI_ALIAS_FLAG: Int = 1
        const val FILTER_BITMAP_FLAG: Int = 2
        const val DITHER_FLAG: Int = 4
    }
}
