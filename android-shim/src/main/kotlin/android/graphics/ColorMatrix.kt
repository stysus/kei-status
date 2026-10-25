package android.graphics

import java.awt.image.BufferedImage

/** Stand-in for android.graphics.ColorFilter. */
open class ColorFilter

/**
 * Stand-in for android.graphics.ColorMatrix: a 4x5 matrix applied to RGBA, where the fifth column
 * is a translation in 0..255 units.
 */
class ColorMatrix(matrix: FloatArray? = null) {

    internal var values: FloatArray = matrix?.copyOf() ?: FloatArray(20).also {
        it[0] = 1f
        it[6] = 1f
        it[12] = 1f
        it[18] = 1f
    }

    fun set(matrix: FloatArray) {
        require(matrix.size == 20) { "ColorMatrix must have 20 values" }
        values = matrix.copyOf()
    }

    fun set(src: ColorMatrix) {
        values = src.values.copyOf()
    }

    fun setScale(rScale: Float, gScale: Float, bScale: Float, aScale: Float) {
        values = floatArrayOf(
            rScale, 0f, 0f, 0f, 0f,
            0f, gScale, 0f, 0f, 0f,
            0f, 0f, bScale, 0f, 0f,
            0f, 0f, 0f, aScale, 0f,
        )
    }

    fun setSaturation(sat: Float) {
        val invSat = 1 - sat
        val r = 0.213f * invSat
        val g = 0.715f * invSat
        val b = 0.072f * invSat
        values = floatArrayOf(
            r + sat, g, b, 0f, 0f,
            r, g + sat, b, 0f, 0f,
            r, g, b + sat, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    operator fun get(index: Int): Float = values[index]

    operator fun set(index: Int, value: Float) {
        values[index] = value
    }
}

/** Stand-in for android.graphics.ColorMatrixColorFilter. */
class ColorMatrixColorFilter(matrix: ColorMatrix) : ColorFilter() {

    constructor(values: FloatArray) : this(ColorMatrix(values))

    private val values: FloatArray = matrix.values.copyOf()

    /** Applies the matrix to a copy of [source], leaving the original untouched. */
    internal fun apply(source: BufferedImage): BufferedImage {
        val w = source.width
        val h = source.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val v = values

        val pixels = source.getRGB(0, 0, w, h, null, 0, w)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val na = (v[15] * r + v[16] * g + v[17] * b + v[18] * a + v[19]).toInt()
            val nr = (v[0] * r + v[1] * g + v[2] * b + v[3] * a + v[4]).toInt()
            val ng = (v[5] * r + v[6] * g + v[7] * b + v[8] * a + v[9]).toInt()
            val nb = (v[10] * r + v[11] * g + v[12] * b + v[13] * a + v[14]).toInt()

            pixels[i] = (na.coerceIn(0, 255) shl 24) or
                (nr.coerceIn(0, 255) shl 16) or
                (ng.coerceIn(0, 255) shl 8) or
                nb.coerceIn(0, 255)
        }

        out.setRGB(0, 0, w, h, pixels, 0, w)
        return out
    }
}
