package kei.status.engine

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeoutException

/** Outcome of classifying a thrown exception. */
data class Diagnosis(
    val status: Status,
    val message: String,
    val chain: List<String>,
)

/**
 * Contract checks from CONTRIBUTING.md, expressed as plain functions over parsed results.
 *
 * These return notes rather than throwing: a missing thumbnail is a WARN, while a missing manga
 * URL breaks library entries and is a FAIL.
 */
object Checks {

    fun mangaPage(page: MangasPage): List<String> {
        val notes = mutableListOf<String>()

        if (page.mangas.isEmpty()) notes += "popular/latest returned an empty list"
        if (page.hasNextPage && page.mangas.isEmpty()) notes += "hasNextPage=true but list is empty"

        page.mangas.take(5).forEachIndexed { index, manga ->
            if (manga.url.isBlank()) notes += "manga[$index] has a blank url"
            if (manga.title.isBlank()) notes += "manga[$index] has a blank title"
            if (manga.thumbnail_url.isNullOrBlank()) notes += "manga[$index] has no thumbnail"
        }

        val duplicates = page.mangas.groupBy { it.url }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) notes += "duplicate manga urls: ${duplicates.take(3)}"

        return notes
    }

    fun chapters(chapters: List<SChapter>): List<String> {
        val notes = mutableListOf<String>()

        if (chapters.isEmpty()) notes += "chapter list is empty"

        chapters.take(5).forEachIndexed { index, chapter ->
            if (chapter.name.isBlank()) notes += "chapter[$index] has a blank name"
            if (chapter.url.isBlank()) notes += "chapter[$index] has a blank url"
            if (chapter.date_upload < 0) notes += "chapter[$index] has a negative date_upload"
        }

        val duplicates = chapters.groupBy { it.url }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) notes += "duplicate chapter urls: ${duplicates.take(3)}"

        return notes
    }

    /**
     * Page URLs may be relative: sources like MangaDex fill `imageUrl` with a server path and
     * resolve it in `getImageRequest`. Only a page with no URL at all is a defect.
     */
    fun pages(pages: List<Page>): List<String> {
        val notes = mutableListOf<String>()

        if (pages.isEmpty()) notes += "page list is empty"

        pages.forEachIndexed { index, page ->
            val imageUrl = page.imageUrl
            if (imageUrl.isNullOrBlank() && page.url.isBlank()) {
                notes += "page[$index] has neither imageUrl nor url"
            } else if (imageUrl != null && imageUrl.contains("://") && imageUrl.toHttpUrlOrNull() == null) {
                notes += "page[$index] imageUrl is not a valid http url: $imageUrl"
            }
        }

        val duplicates = pages.mapNotNull { it.imageUrl }.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) notes += "duplicate page urls: ${duplicates.take(3)}"

        return notes
    }

    /**
     * True when a source refused a chapter because it has to be unlocked first.
     *
     * `SChapter` carries no lock flag, so a paywalled chapter is only identifiable once the source
     * declines to serve it. Around a dozen extensions report it in their own words - "Locked
     * chapter, unlock in WebView", "Chapter is locked", "Chapter locked (coins required)" - and all
     * of them name the chapter. Matching on both words keeps a decryption failure such as "could
     * not unlock the key" classified as the failure it is.
     */
    fun lockedChapter(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "lock" in text && "chapter" in text
    }

    /**
     * True when the source's base URL points at the machine the reader runs it on.
     *
     * Six extensions ship a loopback default - Komga, LANraragi, Stash, Bakkin Self-hosted and two
     * customizable readers - because the reader is expected to run the server themselves. Nothing
     * is published to check, so probing one only ever reports "connection refused", which says the
     * server is absent rather than anything about the extension.
     */
    fun selfHosted(baseUrl: String): Boolean {
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
        return host == "localhost" || host == "0.0.0.0" || host == "::1" || host.startsWith("127.")
    }

    fun metadata(id: Long, name: String, lang: String, baseUrl: String): List<String> = buildList {
        if (id <= 0) add("source id is not positive")
        if (name.isBlank()) add("source name is blank")
        if (!isValidLang(lang)) add("unexpected lang: $lang")
        if (!baseUrl.startsWith("https://")) add("baseUrl is not https: $baseUrl")
        if (baseUrl.toHttpUrlOrNull() == null) add("baseUrl is not a valid url: $baseUrl")
    }

    /**
     * A language code, or one of the two sentinel langs the extension repo uses.
     *
     * `all` is a multi-language source against one site, and `other` is a source with no language
     * at all - Comic Fury's comics are not in any language. Both are declared by real extensions,
     * so flagging them would report a convention as a violation.
     */
    private fun isValidLang(lang: String): Boolean =
        lang == "all" || lang == "other" || lang.matches(Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)?$"))

    /**
     * Maps an exception to a status, a one-line reason, and the cause chain.
     *
     * The chain matters: reflection and static initialisers hide the interesting exception several
     * levels down, and a health report is useless if it only says "InvocationTargetException".
     */
    fun diagnose(t: Throwable): Diagnosis {
        val chain = flatten(t)
        val rendering = chain.map { "${it::class.java.name}: ${it.message ?: "<no message>"}" }

        val status = when {
            chain.any { it is AntiBotChallengeException } -> Status.BLOCKED
            chain.any { it is HttpException && it.code == 429 } -> Status.THROTTLED
            chain.any { it is UnsupportedOperationException || it is NotImplementedError } -> Status.SKIP
            chain.any { it is NoClassDefFoundError || it is ClassNotFoundException } ->
                if (chain.any { unavailablePlatform(it) != null }) Status.UNSUPPORTED else Status.FAIL
            chain.any { it is TimeoutException } -> Status.FAIL
            else -> Status.FAIL
        }

        val reason = reason(status, chain, t)

        // When the reason is the exception's own message, the chain entry for that exception only
        // repeats it ("java.io.IOException: <same text>"). Keep the entries that add context - the
        // wrapping causes and the class names of a different message - and drop the restatement.
        val notes = rendering
            .filterNot { it.substringAfter(": ", "") == reason }
            .distinct()

        return Diagnosis(status, reason, notes)
    }

    private fun reason(status: Status, chain: List<Throwable>, t: Throwable): String = when (status) {
        Status.BLOCKED -> chain.filterIsInstance<AntiBotChallengeException>()
            .firstOrNull()
            ?.let { "${it.provider} anti-bot challenge; the source needs a browser session that passes it" }
            ?: "anti-bot challenge; the source needs a browser session that passes it"

        Status.THROTTLED -> chain.filterIsInstance<HttpException>()
            .firstOrNull { it.code == 429 }
            ?.retryAfterSeconds
            ?.let { "the site is rate-limiting this sweep (HTTP 429, retry after ${it}s); the source was not checked" }
            ?: "the site is rate-limiting this sweep (HTTP 429); the source was not checked"

        Status.SKIP -> "not supported by this source"

        Status.UNSUPPORTED -> chain.firstNotNullOfOrNull { unavailablePlatform(it) }
            ?: "requires a platform class the harness does not provide"

        else -> when (val missing = chain.firstOrNull { it is NoClassDefFoundError || it is ClassNotFoundException }) {
            null -> rootMessage(chain) ?: t::class.java.simpleName
            else -> "missing class: ${missing.message}"
        }
    }

    /** Walks causes, including the ones reflection and static initialisers hide. */
    private fun flatten(t: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = t

        while (current != null && seen.none { it === current }) {
            seen += current
            current = when (current) {
                is java.lang.reflect.InvocationTargetException -> current.targetException ?: current.cause
                is ExceptionInInitializerError -> current.exception ?: current.cause
                else -> current.cause
            }
        }

        return seen
    }

    private fun rootMessage(chain: List<Throwable>): String? =
        chain.asReversed().firstNotNullOfOrNull { it.message }

    /**
     * Describes a missing class the harness cannot supply, or null if the gap is a real bug.
     *
     * The distinction matters: an extension that needs `android.graphics.pdf.PdfRenderer` is not
     * broken, it needs a platform this tool does not emulate. A missing `keiyoushi.*` or
     * `okhttp3.*` class would instead mean the harness classpath is wrong.
     */
    private fun unavailablePlatform(t: Throwable): String? {
        val className = (t.message ?: return null).replace('/', '.')

        return when {
            className.startsWith("android.") || className.startsWith("androidx.") ->
                "requires an Android class the harness does not provide: $className"

            className.startsWith("app.cash.quickjs.") ->
                "requires the native QuickJS library, which has no JVM build: $className"

            else -> null
        }
    }
}
