package android.util

/** Stand-in for android.util.DisplayMetrics. */
class DisplayMetrics {
    @JvmField var widthPixels: Int = 1280
    @JvmField var heightPixels: Int = 1920
    @JvmField var density: Float = 2f
    @JvmField var densityDpi: Int = 320
    @JvmField var scaledDensity: Float = 2f
    @JvmField var xdpi: Float = 320f
    @JvmField var ydpi: Float = 320f
}
