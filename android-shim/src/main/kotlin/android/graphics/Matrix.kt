package android.graphics

import java.awt.geom.AffineTransform

/**
 * Stand-in for android.graphics.Matrix.
 *
 * Android composes as `M' = X * M` for `postX`, which is java.awt's `preConcatenate`. Getting this
 * backwards mirrors every descrambled tile, so the direction is load-bearing.
 *
 * The `post*`/`pre*` methods return `boolean` because Android's do. Skia reports failure only when
 * it cannot allocate, so the honest answer here is always `true`; the return type is part of the
 * JVM descriptor, and extension bytecode compiled against android.jar links against it. `mapRect`
 * is the one operation whose result means something - see below.
 */
class Matrix(private var transform: AffineTransform = AffineTransform()) {

    constructor(other: Matrix) : this(AffineTransform(other.transform))

    fun reset() {
        transform = AffineTransform()
    }

    fun set(other: Matrix) {
        transform = AffineTransform(other.transform)
    }

    fun postScale(sx: Float, sy: Float): Boolean {
        post(AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble()))
        return true
    }

    fun postScale(sx: Float, sy: Float, px: Float, py: Float): Boolean {
        post(
            AffineTransform().apply {
                translate(px.toDouble(), py.toDouble())
                scale(sx.toDouble(), sy.toDouble())
                translate(-px.toDouble(), -py.toDouble())
            },
        )
        return true
    }

    fun preScale(sx: Float, sy: Float): Boolean {
        transform.scale(sx.toDouble(), sy.toDouble())
        return true
    }

    fun postTranslate(dx: Float, dy: Float): Boolean {
        post(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble()))
        return true
    }

    fun preTranslate(dx: Float, dy: Float): Boolean {
        transform.translate(dx.toDouble(), dy.toDouble())
        return true
    }

    fun postRotate(degrees: Float): Boolean {
        post(rotation(degrees.toDouble(), 0.0, 0.0))
        return true
    }

    fun postRotate(degrees: Float, px: Float, py: Float): Boolean {
        post(rotation(degrees.toDouble(), px.toDouble(), py.toDouble()))
        return true
    }

    fun setScale(sx: Float, sy: Float) {
        transform = AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble())
    }

    fun setScale(sx: Float, sy: Float, px: Float, py: Float) {
        transform = AffineTransform().apply {
            translate(px.toDouble(), py.toDouble())
            scale(sx.toDouble(), sy.toDouble())
            translate(-px.toDouble(), -py.toDouble())
        }
    }

    fun setTranslate(dx: Float, dy: Float) {
        transform = AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble())
    }

    fun setRotate(degrees: Float) {
        transform = rotation(degrees.toDouble(), 0.0, 0.0)
    }

    fun setRotate(degrees: Float, px: Float, py: Float) {
        transform = rotation(degrees.toDouble(), px.toDouble(), py.toDouble())
    }

    fun postConcat(other: Matrix): Boolean {
        post(other.transform)
        return true
    }

    fun preConcat(other: Matrix): Boolean {
        transform.concatenate(other.transform)
        return true
    }

    /**
     * Whether this matrix maps every rectangle to another rectangle.
     *
     * Android's answer, and the meaning of [mapRect]'s return: true for identity, scale and
     * translate, and for rotations by a multiple of 90 degrees. In terms of the linear part that is
     * "no rotation or shear" (diagonal) or "nothing but a quarter turn, scaled" (anti-diagonal).
     */
    fun rectStaysRect(): Boolean {
        val m00 = transform.scaleX
        val m01 = transform.shearY
        val m10 = transform.shearX
        val m11 = transform.scaleY
        return (m01 == 0.0 && m10 == 0.0) || (m00 == 0.0 && m11 == 0.0)
    }

    /**
     * Maps the rectangle in place.
     *
     * The result is [rectStaysRect], not "is the rect non-empty": Android documents the return as
     * "the result of calling rectStaysRect()", so it reports whether the corners still form an
     * axis-aligned rectangle rather than how much of the tile survived.
     */
    fun mapRect(rect: RectF): Boolean {
        val points = doubleArrayOf(
            rect.left.toDouble(), rect.top.toDouble(),
            rect.right.toDouble(), rect.bottom.toDouble(),
        )
        transform.transform(points, 0, points, 0, 2)
        rect.set(
            minOf(points[0], points[2]).toFloat(),
            minOf(points[1], points[3]).toFloat(),
            maxOf(points[0], points[2]).toFloat(),
            maxOf(points[1], points[3]).toFloat(),
        )
        return rectStaysRect()
    }

    internal fun toAffineTransform(): AffineTransform = AffineTransform(transform)

    private fun post(other: AffineTransform) {
        transform.preConcatenate(other)
    }

    /**
     * Rotation about a pivot, with [cosineSine] keeping quarter turns exact.
     */
    private fun rotation(degrees: Double, px: Double, py: Double): AffineTransform {
        val (cos, sin) = cosineSine(degrees)
        return AffineTransform(
            cos, sin, -sin, cos,
            px - cos * px + sin * py,
            py - sin * px - cos * py,
        )
    }

    /**
     * The cosine and sine of [degrees], exact for multiples of 90.
     *
     * java.awt computes them, which leaves 6e-17 where Skia has an exact zero. That noise turns a
     * quarter turn into a resampling pass and would make [rectStaysRect] answer false where Android
     * answers true, so the quarter turns come from a table.
     */
    private fun cosineSine(degrees: Double): Pair<Double, Double> {
        val quarterTurns = degrees / 90.0
        if (quarterTurns != Math.floor(quarterTurns) || quarterTurns.isInfinite()) {
            val radians = Math.toRadians(degrees)
            return Math.cos(radians) to Math.sin(radians)
        }
        return when (((quarterTurns.toInt() % 4) + 4) % 4) {
            0 -> 1.0 to 0.0
            1 -> 0.0 to 1.0
            2 -> -1.0 to 0.0
            else -> 0.0 to -1.0
        }
    }
}
