package android.graphics

/** Stand-in for android.graphics.Typeface. Font metrics are delegated to java.awt at draw time. */
class Typeface internal constructor(internal val awtFamily: String) {

    companion object {
        @JvmField val DEFAULT: Typeface = Typeface(java.awt.Font.SANS_SERIF)
        @JvmField val DEFAULT_BOLD: Typeface = Typeface(java.awt.Font.SANS_SERIF)
        @JvmField val SANS_SERIF: Typeface = Typeface(java.awt.Font.SANS_SERIF)
        @JvmField val SERIF: Typeface = Typeface(java.awt.Font.SERIF)
        @JvmField val MONOSPACE: Typeface = Typeface(java.awt.Font.MONOSPACED)

        const val NORMAL: Int = 0
        const val BOLD: Int = 1
        const val ITALIC: Int = 2
        const val BOLD_ITALIC: Int = 3

        @JvmStatic
        fun create(familyName: String?, style: Int): Typeface =
            Typeface(familyName ?: java.awt.Font.SANS_SERIF)

        @JvmStatic
        fun create(family: Typeface?, style: Int): Typeface =
            family ?: DEFAULT

        @JvmStatic
        fun createFromFile(path: String): Typeface? = createFromFile(java.io.File(path))

        @JvmStatic
        fun createFromFile(file: java.io.File): Typeface? =
            if (file.isFile) Typeface(file.absolutePath) else null
    }
}
