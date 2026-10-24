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

    /**
     * Copies an ARGB block out of the bitmap, the way Android's `getPixels` does.
     *
     * SoraRaw's descrambler works entirely on `IntArray` pixels - it reads the whole page, permutes
     * the block AND the channels, then rebuilds a bitmap - so without this the source fails to link
     * rather than producing a wrong image.
     */
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        check(!recycled) { "Can't call getPixels() on a recycled bitmap" }
        val source = requireImage()
        if (width == 0 || height == 0) return

        checkPixelsAccess(source, x, y, width, height, offset, stride, pixels)
        for (row in 0 until height) {
            for (column in 0 until width) {
                pixels[offset + row * stride + column] = source.getRGB(x + column, y + row)
            }
        }
    }

    /**
     * Writes an ARGB block into the bitmap, the way Android's `setPixels` does.
     *
     * `:lib:e4p` composites decoded WebP patches into a page with this call, which is what J-Novel
     * and the other e4p-based sources need to finish a page. The rows are copied one at a time
     * because java.awt rejects the negative stride Android documents (`abs(stride) >= width` is
     * the only rule Android applies); a padded or reversed array would otherwise write the wrong
     * pixels instead of failing.
     */
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        check(!recycled) { "Can't call setPixels() on a recycled bitmap" }
        if (!mutable) throw IllegalStateException()
        if (width == 0 || height == 0) return

        val target = requireImage()
        checkPixelsAccess(target, x, y, width, height, offset, stride, pixels)

        val row = IntArray(width)
        for (index in 0 until height) {
            System.arraycopy(pixels, offset + index * stride, row, 0, width)
            target.setRGB(x, y + index, width, 1, row, 0, width)
        }
    }

    /** Android's own bounds contract, so a bad call fails loudly instead of reading past the row. */
    private fun checkPixelsAccess(
        image: BufferedImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        offset: Int,
        stride: Int,
        pixels: IntArray,
    ) {
        if (width < 0 || height < 0) throw IllegalArgumentException("width and height must be >= 0")
        if (x < 0 || x + width > image.width) {
            throw IllegalArgumentException("x + width must be <= bitmap.width()")
        }
        if (y < 0 || y + height > image.height) {
            throw IllegalArgumentException("y + height must be <= bitmap.height()")
        }
        if (kotlin.math.abs(stride) < width) throw IllegalArgumentException("abs(stride) must be >= width")
        val lastScanline = offset + (height - 1) * stride
        if (offset < 0 || offset + width > pixels.size || lastScanline < 0 || lastScanline + width > pixels.size) {
            throw ArrayIndexOutOfBoundsException()
        }
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
        val writer = ImageIO.getImageWritersByFormatName(format.imageIoName).let {
            if (it.hasNext()) it.next() else null
        } ?: return false

        val source = writableImage(format, requireImage())
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

    /**
     * The image handed to the encoder, made encodable.
     *
     * Android's JPEG encoder takes any bitmap and simply drops the alpha. The JDK's one refuses
     * the alpha-backed types outright - `writer.write` on a `TYPE_INT_ARGB` image throws
     * `IIOException: Bogus input colorspace` - so an overlay interceptor that decodes an alpha
     * image and re-encodes it as JPEG would fail where the app succeeds. Flattening onto opaque
     * RGB is what the app's output looks like anyway: JPEG has no alpha channel.
     */
    private fun writableImage(format: CompressFormat, source: BufferedImage): BufferedImage {
        if (format != CompressFormat.JPEG || source.type in JPEG_WRITABLE_TYPES) return source
        val flattened = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = flattened.createGraphics()
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
        return flattened
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

        /** Types the JDK's JPEG writer accepts directly; everything else gets flattened first. */
        private val JPEG_WRITABLE_TYPES = setOf(
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_INT_BGR,
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_BYTE_INDEXED,
            BufferedImage.TYPE_BYTE_BINARY,
            BufferedImage.TYPE_USHORT_565_RGB,
        )

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap =
            createBitmap(width, height, config, hasAlpha = true)

        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config, hasAlpha: Boolean): Bitmap {
            require(width > 0 && height > 0) { "width and height must be > 0" }
            val type = if (hasAlpha) config.imageType else BufferedImage.TYPE_INT_RGB
            return Bitmap(BufferedImage(width, height, type), config, mutable = true)
        }

        /**
         * Wraps an ARGB pixel array. SoraRaw builds its descrambled page this way: it permutes an
         * `IntArray` and hands it back, so the array form is the whole point of its interceptor.
         */
        @JvmStatic
        fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): Bitmap =
            createBitmap(pixels, 0, width, width, height, config)

        /**
         * The strided array overload Android also exposes: `colors[offset + row * stride + column]`
         * fills pixel (column, row). [BufferedImage.setRGB] takes the same offset and scanline
         * stride, so the mapping is direct and no copy of the array is needed.
         */
        @JvmStatic
        fun createBitmap(colors: IntArray, offset: Int, stride: Int, width: Int, height: Int, config: Config): Bitmap {
            require(width > 0 && height > 0) { "width and height must be > 0" }
            require(offset >= 0) { "offset must be >= 0" }
            require(stride >= width) { "stride must be >= width" }
            require(offset + (height - 1) * stride + width <= colors.size) {
                "colors array is too small: ${colors.size} < ${offset + (height - 1) * stride + width}"
            }

            val image = BufferedImage(width, height, config.imageType)
            image.setRGB(0, 0, width, height, colors, offset, stride)
            return Bitmap(image, config, mutable = true)
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
