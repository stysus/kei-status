package android.graphics

/**
 * Stand-in for android.graphics.Color.
 *
 * Colours are Android's packed ARGB ints; only the constant and packing helpers that extensions
 * actually call are provided.
 */
object Color {

    @JvmField val BLACK: Int = -0x1000000
    @JvmField val DKGRAY: Int = -0x444444
    @JvmField val GRAY: Int = -0x777778
    @JvmField val LTGRAY: Int = -0x333334
    @JvmField val WHITE: Int = -0x1
    @JvmField val RED: Int = -0x10000
    @JvmField val GREEN: Int = -0xff0100
    @JvmField val BLUE: Int = -0xffff01
    @JvmField val YELLOW: Int = -0x100
    @JvmField val CYAN: Int = -0xff0001
    @JvmField val MAGENTA: Int = -0xff01
    @JvmField val TRANSPARENT: Int = 0

    @JvmStatic
    fun alpha(color: Int): Int = color ushr 24

    @JvmStatic
    fun red(color: Int): Int = (color shr 16) and 0xFF

    @JvmStatic
    fun green(color: Int): Int = (color shr 8) and 0xFF

    @JvmStatic
    fun blue(color: Int): Int = color and 0xFF

    @JvmStatic
    fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or (blue and 0xFF)

    /** Supports `#RGB`, `#ARGB`, `#RRGGBB` and `#AARRGGBB`. */
    @JvmStatic
    fun parseColor(colorString: String): Int {
        require(colorString.startsWith("#")) { "Unknown color: $colorString" }
        val hex = colorString.substring(1)
        val value = hex.toLong(16)
        return when (hex.length) {
            3 -> argb(255, ((value shr 8) and 0xF).toInt() * 17, ((value shr 4) and 0xF).toInt() * 17, (value and 0xF).toInt() * 17)
            4 -> argb(((value shr 12) and 0xF).toInt() * 17, ((value shr 8) and 0xF).toInt() * 17, ((value shr 4) and 0xF).toInt() * 17, (value and 0xF).toInt() * 17)
            6 -> (value or 0xFF000000L).toInt()
            8 -> value.toInt()
            else -> throw IllegalArgumentException("Unknown color: $colorString")
        }
    }

    @JvmStatic
    fun colorToHSV(color: Int, hsv: FloatArray) {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        hsv[2] = max
        hsv[1] = if (max == 0f) 0f else delta / max
        hsv[0] = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
    }
}
