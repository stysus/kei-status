package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * Host-side base class for online sources.
 *
 * Mirrors the app's implementation closely enough that extension bytecode links and behaves the
 * same. Two details are load-bearing and easy to get wrong:
 *
 *  - [headers] must stay a `by lazy` property: `KeiSource` reflects on the generated
 *    `headers$delegate` field to replace it with an eagerly built value.
 *  - [network] must resolve to a client carrying interceptors named `UncaughtExceptionInterceptor`,
 *    `UserAgentInterceptor`, and `CloudflareInterceptor`, because `KeiSource` asserts on those
 *    names before building its own client.
 */
abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient get() = network.client

    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    open suspend fun getImageUrl(page: Page): String =
        throw UnsupportedOperationException("getImageUrl not implemented")

    /**
     * The request the app uses to download a page image.
     *
     * Exists only on the app-side class, not in `extensions-lib`, so extensions that override the
     * protected [imageRequest] (MangaDex resolves At-Home URLs there, with a relative `imageUrl`)
     * depend on this being present and public.
     */
    open fun getImageRequest(page: Page): Request = imageRequest(page)

    protected open fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers)
        .get()
        .build()

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig.replace(" ", "%20"))
        buildString {
            append(uri.rawPath)
            uri.rawQuery?.let { append("?").append(it) }
            uri.rawFragment?.let { append("#").append(it) }
        }
    } catch (_: URISyntaxException) {
        orig
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String
}
