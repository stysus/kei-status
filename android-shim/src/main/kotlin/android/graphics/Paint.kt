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

    private var colorFilterValue: ColorFilter? = null

    var textSize: Float = 12f

    /**
     * Horizontal glyph stretch, as `setTextScaleX(float)` on the framework.
     *
     * Rawkuma's text overlay measures a line and sets this to the ratio that stretches it to the
     * SVG's exact `textLength`, so both the member and its effect on [measureText] are modelled.
     * [Canvas.drawText] ignores the stretch - the harness verifies that an image is produced and
     * decodes, not that its glyphs land on the same pixels - but measuring has to agree with the
     * scale or the computed ratio would be wrong by the previous scale factor.
     */
    var textScaleX: Float = 1f

    private var typefaceValue: Typeface? = Typeface.DEFAULT

    var strokeCap: Cap = Cap.BUTT

    var strokeJoin: Join = Join.MITER

    constructor()

    constructor(flags: Int) {
        isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
        isFilterBitmap = flags and FILTER_BITMAP_FLAG != 0
        isDither = flags and DITHER_FLAG != 0
    }

    /**
     * Android's copy constructor, `Paint(from)`. Rawkuma derives its stroke paint from the fill
     * one this way; missing it turned into a `NoSuchMethodError` mid-scenario.
     */
    constructor(from: Paint) {
        color = from.color
        alpha = from.alpha
        style = from.style
        strokeWidth = from.strokeWidth
        isAntiAlias = from.isAntiAlias
        isFilterBitmap = from.isFilterBitmap
        isDither = from.isDither
        colorFilterValue = from.colorFilterValue
        textSize = from.textSize
        textScaleX = from.textScaleX
        typefaceValue = from.typefaceValue
        strokeCap = from.strokeCap
        strokeJoin = from.strokeJoin
    }

    /**
     * Android's `setTypeface` returns the typeface it was given, not `void`.
     *
     * That return type is part of the JVM descriptor, so this cannot be a Kotlin `var`: the setter
     * a property generates is `void`, and extension bytecode compiled against android.jar links
     * against `setTypeface(Typeface)Typeface`. The same holds for every fluent setter in this
     * class - see [setColorFilter] - which is why they are written as methods, not properties.
     */
    fun getTypeface(): Typeface? = typefaceValue

    fun setTypeface(typeface: Typeface?): Typeface? {
        typefaceValue = typeface
        return typeface
    }

    fun getColorFilter(): ColorFilter? = colorFilterValue

    fun setColorFilter(colorFilter: ColorFilter?): ColorFilter? {
        colorFilterValue = colorFilter
        return colorFilter
    }

    /**
     * The `java.awt` font this paint describes.
     *
     * Shared with [android.text.StaticLayout], which must measure with exactly the font the canvas
     * later draws with; two independent mappings would wrap the text at the wrong column.
     */
    internal fun awtFont(): java.awt.Font = java.awt.Font(
        typefaceValue?.awtFamily ?: java.awt.Font.SANS_SERIF,
        java.awt.Font.PLAIN,
        textSize.toInt().coerceAtLeast(1),
    )

    fun reset() {
        color = Color.BLACK
        alpha = 255
        style = Style.FILL
        strokeWidth = 0f
        isAntiAlias = false
        isFilterBitmap = true
        isDither = false
        colorFilterValue = null
        textSize = 12f
        textScaleX = 1f
        typefaceValue = Typeface.DEFAULT
    }

    fun setARGB(a: Int, r: Int, g: Int, b: Int) {
        color = Color.argb(a, r, g, b)
    }

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) = Unit

    /**
     * The advance width of [text] in the font this paint draws with, scaled by [textScaleX].
     *
     * Measures through the same `awtFont()` [android.text.StaticLayout] lays out with, so a line
     * measured here and one wrapped there agree. The metrics need a graphics context for a moment,
     * which is created and dropped per call - a shared one would be a race for the background work
     * sources start.
     */
    fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        val graphics = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics()
        val width = try {
            graphics.getFontMetrics(awtFont()).stringWidth(text).toFloat()
        } finally {
            graphics.dispose()
        }
        return width * textScaleX
    }

    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    enum class Cap { BUTT, ROUND, SQUARE }

    enum class Join { MITER, ROUND, BEVEL }

    companion object {
        const val ANTI_ALIAS_FLAG: Int = 1
        const val FILTER_BITMAP_FLAG: Int = 2
        const val DITHER_FLAG: Int = 4
    }
}
