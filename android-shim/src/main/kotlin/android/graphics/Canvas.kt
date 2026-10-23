package android.graphics

import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D

/**
 * Stand-in for android.graphics.Canvas, drawing into a [Bitmap]'s BufferedImage.
 *
 * `drawBitmap(bitmap, src, dst, paint)` is the operation extensions actually depend on: it crops
 * `src` and scales it into `dst`, which is exactly how a scrambled tile gets placed back.
 */
class Canvas(val bitmap: Bitmap) {

    private val graphics: Graphics2D = bitmap.requireImage().createGraphics()

    private class State(val transform: AffineTransform, val clip: Shape?)

    private val stack = ArrayDeque<State>()

    fun save(): Int {
        stack.addLast(State(graphics.transform, graphics.clip))
        return stack.size
    }

    fun restore() {
        val state = stack.removeLastOrNull() ?: return
        graphics.transform = state.transform
        graphics.clip = state.clip
    }

    fun restoreToCount(saveCount: Int) {
        while (stack.size > saveCount) restore()
    }

    fun getSaveCount(): Int = stack.size + 1

    fun translate(dx: Float, dy: Float) = graphics.translate(dx.toDouble(), dy.toDouble())

    fun scale(sx: Float, sy: Float) = graphics.scale(sx.toDouble(), sy.toDouble())

    fun rotate(degrees: Float) = graphics.rotate(Math.toRadians(degrees.toDouble()))

    fun rotate(degrees: Float, px: Float, py: Float) =
        graphics.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())

    fun clipRect(rect: RectF) {
        graphics.clip(Rectangle2D.Float(rect.left, rect.top, rect.width(), rect.height()))
    }

    fun clipRect(left: Float, top: Float, right: Float, bottom: Float) {
        graphics.clip(Rectangle2D.Float(left, top, right - left, bottom - top))
    }

    fun drawColor(color: Int) {
        val saved = graphics.color
        graphics.color = java.awt.Color(color, true)
        graphics.fillRect(0, 0, bitmap.width, bitmap.height)
        graphics.color = saved
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF?, paint: Paint?) {
        if (dst == null) return
        drawScaled(bitmap, src, dst.left, dst.top, dst.right, dst.bottom, paint)
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect?, paint: Paint?) {
        if (dst == null) return
        drawScaled(
            bitmap,
            src,
            dst.left.toFloat(),
            dst.top.toFloat(),
            dst.right.toFloat(),
            dst.bottom.toFloat(),
            paint,
        )
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val image = prepare(bitmap, paint) ?: return
        applyPaint(paint)
        graphics.drawImage(
            image,
            AffineTransform.getTranslateInstance(left.toDouble(), top.toDouble()),
            null,
        )
    }

    fun drawBitmap(bitmap: Bitmap, matrix: Matrix?, paint: Paint?) {
        val image = prepare(bitmap, paint) ?: return
        applyPaint(paint)
        val transform = AffineTransform(graphics.transform)
        matrix?.let { transform.concatenate(it.toAffineTransform()) }
        graphics.drawImage(image, transform, null)
    }

    fun drawRect(rect: RectF, paint: Paint) {
        applyPaint(paint)
        graphics.draw(Rectangle2D.Float(rect.left, rect.top, rect.width(), rect.height()))
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        applyPaint(paint)
        graphics.draw(Rectangle2D.Float(left, top, right - left, bottom - top))
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        applyPaint(paint)
        graphics.drawString(text, x, y)
    }

    private fun drawScaled(
        bitmap: Bitmap,
        src: Rect?,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint?,
    ) {
        val image = prepare(bitmap, paint) ?: return
        val srcX = src?.left ?: 0
        val srcY = src?.top ?: 0
        val srcW = src?.width() ?: image.width
        val srcH = src?.height() ?: image.height
        if (srcW <= 0 || srcH <= 0 || right <= left || bottom <= top) return

        val savedTransform = graphics.transform
        val savedClip = graphics.clip
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (paint?.isFilterBitmap != false) {
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            } else {
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            },
        )
        graphics.clip(Rectangle2D.Float(left, top, right - left, bottom - top))

        val transform = AffineTransform(savedTransform)
        transform.translate(left.toDouble(), top.toDouble())
        transform.scale((right - left).toDouble() / srcW, (bottom - top).toDouble() / srcH)
        transform.translate(-srcX.toDouble(), -srcY.toDouble())

        graphics.drawImage(image, transform, null)
        graphics.transform = savedTransform
        graphics.clip = savedClip
    }

    /** Returns the image to draw, with any colour filter already baked in. */
    private fun prepare(bitmap: Bitmap, paint: Paint?): java.awt.image.BufferedImage? {
        val image = try {
            bitmap.requireImage()
        } catch (_: IllegalStateException) {
            return null
        }
        return (paint?.getColorFilter() as? ColorMatrixColorFilter)?.apply(image) ?: image
    }

    private fun applyPaint(paint: Paint?) {
        paint ?: return
        graphics.color = java.awt.Color(
            Color.red(paint.color),
            Color.green(paint.color),
            Color.blue(paint.color),
            (paint.alpha * Color.alpha(paint.color) / 255),
        )
        graphics.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            if (paint.isAntiAlias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF,
        )
        graphics.font = paint.awtFont()
        graphics.stroke = if (paint.style == Paint.Style.FILL) {
            BasicStroke(0f)
        } else {
            BasicStroke(paint.strokeWidth)
        }
    }
}
