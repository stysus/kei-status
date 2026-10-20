package android.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * Stand-in for android.graphics.BitmapFactory.
 *
 * Decoding goes through ImageIO, so WEBP support depends on the ImageIO plugin being present -
 * the same formats Android ships natively are covered once that plugin is on the classpath.
 */
object BitmapFactory {

    class Options {
        @JvmField
        var inJustDecodeBounds: Boolean = false

        @JvmField
        var inSampleSize: Int = 1

        @JvmField
        var inPreferredConfig: Bitmap.Config? = null

        @JvmField
        var inMutable: Boolean = false

        @JvmField
        var outWidth: Int = -1

        @JvmField
        var outHeight: Int = -1

        @JvmField
        var outMimeType: String? = null
    }

    @JvmStatic
    @JvmOverloads
    fun decodeStream(
        stream: InputStream?,
        outPadding: Rect? = null,
        options: Options? = null,
    ): Bitmap? {
        if (stream == null) return null
        // ImageIO needs a re-readable stream when it probes the format, and callers hand us
        // network bodies that may not support mark/reset.
        val bytes = stream.readBytes()
        return decodeByteArray(bytes, 0, bytes.size, options)
    }

    @JvmStatic
    @JvmOverloads
    fun decodeByteArray(
        data: ByteArray,
        offset: Int,
        length: Int,
        options: Options? = null,
    ): Bitmap? {
        if (length <= 0) return null

        if (options?.inJustDecodeBounds == true) {
            val size = readSize(data, offset, length) ?: return null
            options.outWidth = size.first
            options.outHeight = size.second
            return null
        }

        val decoded = ImageIO.read(java.io.ByteArrayInputStream(data, offset, length)) ?: return null

        val config = options?.inPreferredConfig ?: Bitmap.Config.ARGB_8888
        val sample = options?.inSampleSize?.coerceAtLeast(1) ?: 1
        val target = if (sample > 1) {
            BufferedImage(
                decoded.width / sample,
                decoded.height / sample,
                config.imageType,
            ).also { scaled ->
                scaled.createGraphics().use { g ->
                    g.drawImage(decoded, 0, 0, scaled.width, scaled.height, null)
                }
            }
        } else {
            toConfig(decoded, config)
        }

        options?.let {
            it.outWidth = target.width
            it.outHeight = target.height
        }

        return Bitmap(target, config, options?.inMutable ?: false)
    }

    @JvmStatic
    fun decodeFile(pathName: String): Bitmap? =
        java.io.File(pathName).takeIf { it.isFile }?.inputStream()?.use { decodeStream(it) }

    private fun toConfig(image: BufferedImage, config: Bitmap.Config): BufferedImage {
        if (image.type == config.imageType) return image
        return BufferedImage(image.width, image.height, config.imageType).also { converted ->
            converted.createGraphics().use { g -> g.drawImage(image, 0, 0, null) }
        }
    }

    /** Reads dimensions without decoding pixels. */
    private fun readSize(data: ByteArray, offset: Int, length: Int): Pair<Int, Int>? = try {
        val stream = ImageIO.createImageInputStream(java.io.ByteArrayInputStream(data, offset, length))
        val readers = ImageIO.getImageReaders(stream)
        if (readers.hasNext()) {
            val reader = readers.next()
            reader.input = stream
            try {
                Pair(reader.getWidth(0), reader.getHeight(0))
            } finally {
                reader.dispose()
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** Small helper so `createGraphics()` is always disposed. */
    private inline fun <T> java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> T): T =
        try {
            block(this)
        } finally {
            dispose()
        }
}
