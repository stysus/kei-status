package android.graphics

/**
 * Stand-in for android.graphics.Rect.
 *
 * `width()`/`height()`/`centerX()` are methods here, not Kotlin properties, because that is what
 * Android's Java class exposes and therefore what extension bytecode invokes.
 */
open class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0,
) {

    constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(other: Rect) = set(other.left, other.top, other.right, other.bottom)

    fun setEmpty() = set(0, 0, 0, 0)

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun centerX(): Int = (left + right) shr 1

    fun centerY(): Int = (top + bottom) shr 1

    /**
     * The float centre Android exposes next to the integer one.
     *
     * ebookjapan rotates every tile around `dst.exactCenterX()`, so without it the descrambler
     * fails to link rather than drawing a rotated page.
     */
    fun exactCenterX(): Float = (left + right) * 0.5f

    fun exactCenterY(): Float = (top + bottom) * 0.5f

    fun isEmpty(): Boolean = left >= right || top >= bottom

    override fun equals(other: Any?): Boolean =
        other is Rect && left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom

    override fun hashCode(): Int = (((left * 31 + top) * 31 + right) * 31) + bottom

    override fun toString(): String = "Rect($left, $top - $right, $bottom)"
}

/** Stand-in for android.graphics.RectF. */
open class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f,
) {

    constructor(left: Int, top: Int, right: Int, bottom: Int) :
        this(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    constructor(other: RectF) : this(other.left, other.top, other.right, other.bottom)

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(other: RectF) = set(other.left, other.top, other.right, other.bottom)

    fun setEmpty() = set(0f, 0f, 0f, 0f)

    fun width(): Float = right - left

    fun height(): Float = bottom - top

    fun centerX(): Float = (left + right) * 0.5f

    fun centerY(): Float = (top + bottom) * 0.5f

    fun isEmpty(): Boolean = left >= right || top >= bottom

    override fun equals(other: Any?): Boolean =
        other is RectF && left == other.left && top == other.top &&
            right == other.right && bottom == other.bottom

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}
