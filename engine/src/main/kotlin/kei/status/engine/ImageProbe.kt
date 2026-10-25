package kei.status.engine

import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource

class Captured<T>(val value: T?, val result: ScenarioResult)

/**
 * Resolves a page's image URL the way the reader does before it downloads.
 *
 * Several extensions fill only `page.url` in `getPageList` and resolve the image lazily in
 * `getImageUrl` - Dragon Ball Multiverse, Akuma and NovelCool all lean on this. The reader's own
 * loader checks `imageUrl.isNullOrEmpty()` first and resolves there; building the image request
 * without that step crashes on the missing URL and reports the source as broken.
 *
 * The resolved URL is written back onto the page so the image request is built against it. The
 * `pages` scenario's samples were captured before this runs, so they still name the page path;
 * the resolved address only shows up in the recorded image requests themselves.
 */
internal suspend fun HttpSource.resolveImageUrl(page: Page) {
    if (page.imageUrl.isNullOrEmpty()) {
        page.imageUrl = getImageUrl(page)
    }
}

/**
 * The AVIF or HEIF brand an undecodable payload carries, or null when it is not one.
 *
 * Android decodes AVIF and HEIF natively, the JVM stack does not, so a source that serves those
 * formats reports "did not decode" here while working fine in the app - the same verdict gap a
 * missing platform class is. Naming the brand turns the note from "the source sent garbage" into
 * the platform gap it actually is.
 *
 * An ISO base media file opens with a `ftyp` box: four size bytes, the literal `ftyp`, then the
 * major brand. `avis` is the AVIF sequence brand; `heic` and `mif1`/`msf1` are the HEIF brands -
 * still images commonly ship as `heic` inside an `mif1` container, so both are named.
 */
internal fun imageFormatName(data: ByteArray): String? {
    if (data.size < 12) return null
    if (data.sliceArray(4..7).toString(Charsets.US_ASCII) != "ftyp") return null

    return when (val brand = data.sliceArray(8..11).toString(Charsets.US_ASCII)) {
        "avif" -> "AVIF"
        "avis" -> "AVIF sequence"
        "heic", "mif1", "msf1" -> "HEIF ($brand)"
        else -> null
    }
}

/**
 * The note a downloaded image body earns, or null when it decoded and there is nothing to say.
 *
 * The header's content-type deliberately does not get its own note on a body that decoded: CDNs
 * mislabel images all the time (octet-stream for real JPEG, e4p's rewritten WEBP keeping its old
 * header), and a note on a healthy body degraded fourteen perfectly working sources to WARN. The
 * type still travels inside the did-not-decode note, where it describes the bytes that failed.
 */
internal fun imageDecodeNote(index: Int, bytes: ByteArray, type: String): String? {
    if (bytes.isEmpty()) return "page[$index] image body is empty"

    // Probe the header only — proving the format is recognised and the dimensions are
    // readable is enough to confirm the source delivered a real image. A full pixel decode
    // would allocate a multi-megabyte BufferedImage per page just to throw it away, and the
    // harness is testing the extension's wiring, not the CDN's integrity.
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth > 0 && opts.outHeight > 0) return null

    val format = imageFormatName(bytes)
    return if (format != null) {
        "page[$index] image is $format, which the harness cannot decode; Android decodes it natively"
    } else {
        "page[$index] image bytes did not decode ('$type')"
    }
}

/**
 * The media type of the bytes a caller is about to read.
 *
 * `Response.Builder.body()` does not copy the body's media type into the `Content-Type` header, so
 * the two can disagree: an interceptor that replaces the bytes keeps the header the server sent.
 * The body is the honest answer to "what did I just receive", and the header only stands in when
 * the body carries no media type at all - which is the case for a response built from raw bytes.
 */
internal fun contentTypeOf(response: okhttp3.Response): String =
    response.body.contentType()?.toString()
        ?: response.header("Content-Type")?.takeIf { it.isNotEmpty() }
        ?: ""
