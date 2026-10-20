package android.graphics

import java.awt.geom.AffineTransform

/**
 * Stand-in for android.graphics.Matrix.
 *
 * Android composes as `M' = X * M` for `postX`, which is java.awt's `preConcatenate`. Getting this
 * backwards mirrors every descrambled tile, so the direction is load-bearing.
 */
class Matrix(private var transform: AffineTransform = AffineTransform()) {

    constructor(other: Matrix) : this(AffineTransform(other.transform))

    fun reset() {
        transform = AffineTransform()
    }

    fun set(other: Matrix) {
        transform = AffineTransform(other.transform)
    }

    fun postScale(sx: Float, sy: Float): Unit = post(AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble()))

    fun postScale(sx: Float, sy: Float, px: Float, py: Float) = post(
        AffineTransform().apply {
            translate(px.toDouble(), py.toDouble())
            scale(sx.toDouble(), sy.toDouble())
            translate(-px.toDouble(), -py.toDouble())
        },
    )

    fun preScale(sx: Float, sy: Float) = transform.scale(sx.toDouble(), sy.toDouble())

    fun postTranslate(dx: Float, dy: Float): Unit =
        post(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble()))

    fun preTranslate(dx: Float, dy: Float) = transform.translate(dx.toDouble(), dy.toDouble())

    fun postRotate(degrees: Float): Unit = post(rotation(degrees.toDouble(), 0.0, 0.0))

    fun postRotate(degrees: Float, px: Float, py: Float): Unit =
        post(rotation(degrees.toDouble(), px.toDouble(), py.toDouble()))

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

    fun postConcat(other: Matrix) = post(other.transform)

    fun preConcat(other: Matrix) = transform.concatenate(other.transform)

    fun mapRect(rect: RectF) {
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
    }

    internal fun toAffineTransform(): AffineTransform = AffineTransform(transform)

    private fun post(other: AffineTransform) {
        transform.preConcatenate(other)
    }

    private fun rotation(degrees: Double, px: Double, py: Double): AffineTransform =
        AffineTransform().apply {
            translate(px, py)
            rotate(Math.toRadians(degrees))
            translate(-px, -py)
        }
}
