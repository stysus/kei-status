package android.graphics

import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * Stand-in for android.graphics.Bitmap, backed by a [BufferedImage].
 *
 * Extensions use Bitmap almost exclusively to descramble page images: decode, tile-shuffle with a
 * [Canvas], re-encode. `java.awt` gives the same source-rect -> destination-rect scaling that
 * `Canvas.drawBitmap` provides, so the tile arithmetic runs unchanged.
 */
class Bitmap internal constructor(
    image: BufferedImage,
    private val config: Config,
    private val mutable: Boolean,
) {

    internal var image: BufferedImage? = image
        private set

    private var recycled = false

    val width: Int get() = requireImage().width

    val height: Int get() = requireImage().height

    fun getConfig(): Config = config

    fun isMutable(): Boolean = mutable

    fun isRecycled(): Boolean = recycled

    /** Matches Android: a recycled bitmap cannot be drawn, which surfaces use-after-free bugs. */
    fun recycle() {
        recycled = true
        image = null
    }

    internal fun requireImage(): BufferedImage {
        check(!recycled) { "Canvas: trying to use a recycled bitmap" }
        return image ?: error("Bitmap has no backing image")
    }

    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val source = requireImage()
        val copy = BufferedImage(source.width, source.height, config.imageType)
        val g = copy.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return Bitmap(copy, config, isMutable)
    }

    /**
     * Encodes to [format] at [quality]. WEBP needs the ImageIO plugin registered on the classpath;
     * without it Android-only sources would fail on an otherwise healthy descramble.
     */
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val source = requireImage()
        val writer = ImageIO.getImageWritersByFormatName(format.imageIoName).let {
            if (it.hasNext()) it.next() else null
        } ?: return false

        val output = ImageIO.createImageOutputStream(stream)
        try {
            writer.output = output
            writer.write(null, javax.imageio.IIOImage(source, null, null), writer.defaultWriteParam.apply {
                if (canWriteCompressed()) {
                    compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                    // WebP's writer refuses setCompressionQuality until a compression type is
                    // chosen; the other formats either have one default or none at all.
                    compressionTypes?.takeIf { it.isNotEmpty() }?.let { types ->
                        compressionType = types.firstOrNull { it.equals("Lossy", ignoreCase = true) } ?: types.first()
                    }
                    compressionQuality = (quality.coerceIn(0, 100)) / 100f
                }
            })
        } finally {
            writer.dispose()
            output?.close()
        }
        return true
    }

    override fun toString(): String = "Bitmap(${width}x${height}, $config)"

    enum class Config(internal val imageType: Int) {
        ALPHA_8(BufferedImage.TYPE_BYTE_GRAY),
        RGB_565(BufferedImage.TYPE_USHORT_565_RGB),
        ARGB_4444(BufferedImage.TYPE_INT_ARGB),
        ARGB_8888(BufferedImage.TYPE_INT_ARGB),
        RGBA_F16(BufferedImage.TYPE_INT_ARGB),
        HARDWARE(BufferedImage.TYPE_INT_ARGB),
    }

    enum class CompressFormat(internal val imageIoName: String) {
        JPEG("jpeg"),
        PNG("png"),
        WEBP("webp"),
    }

    companion object {

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            createBitmap(width, height, config, hasAlpha = true)

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config, hasAlpha: Boolean): Bitmap {
            require(width > 0 && height > 0) { "width and height must be > 0" }
            val type = if (hasAlpha) config.imageType else BufferedImage.TYPE_INT_RGB
            return Bitmap(BufferedImage(width, height, type), config, mutable = true)
        }

        @JvmStatic
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            val image = source.requireImage()
            val cropped = BufferedImage(width, height, image.type)
            val g = cropped.createGraphics()
            try {
                g.drawImage(image, 0, 0, width, height, x, y, x + width, y + height, null)
            } finally {
                g.dispose()
            }
            return Bitmap(cropped, source.getConfig(), mutable = true)
        }

        @JvmStatic
        fun createScaledBitmap(source: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap {
            val image = source.requireImage()
            val scaled = BufferedImage(width, height, image.type)
            val g = scaled.createGraphics()
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    if (filter) {
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    } else {
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    },
                )
                g.drawImage(image, 0, 0, width, height, null)
            } finally {
                g.dispose()
            }
            return Bitmap(scaled, source.getConfig(), mutable = true)
        }
    }
}
