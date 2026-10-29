package kei.status.engine

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

        val duplicates = page.mangas.map { it.url }.filter { it.isNotBlank() }
            .groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) notes += "duplicate manga urls: ${duplicates.sorted().take(3)}"

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

        val duplicates = chapters.map { it.url }.filter { it.isNotBlank() }
            .groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) notes += "duplicate chapter urls: ${duplicates.sorted().take(3)}"

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

        // imageUrl when present, else url: the identity a reader would request twice. MangaDex-family
        // sources keep a unique server path in imageUrl; url-only sources are checked on url. One
        // exception: a list whose pages all share a single url is index-disambiguated resolution
        // (Comix resolves getImageUrl by page.index), not a duplicate — a bug repeats a url among
        // distinct ones.
        val identities = pages.map { it.imageUrl?.takeIf(String::isNotBlank) ?: it.url }
            .filter { it.isNotBlank() }
        val duplicates = identities.groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .filter { identities.distinct().size > 1 }
        if (duplicates.isNotEmpty()) notes += "duplicate page urls: ${duplicates.sorted().take(3)}"

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
     *
     * The same refusal also arrives without the word "lock": the Kadokawa family answers "this
     * chapter is either unavailable or must be purchased", and several others ask the reader to
     * "log in via WebView and purchase this chapter to read". Both say the content needs an
     * account, so they skip the same way - guarded so a parse error that happens to quote a
     * `purchaseUrl` field stays a failure.
     */
    fun lockedChapter(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return ("lock" in text && "chapter" in text) ||
            ("purchas" in text && "to read" in text) ||
            "must be purchased" in text
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
    private val LANG_PATTERN = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)?$")

    private fun isValidLang(lang: String): Boolean =
        lang == "all" || lang == "other" || lang.matches(LANG_PATTERN)

    /** Delegates to [ExceptionDiagnosis] so existing callers need not change their import. */
    fun diagnose(t: Throwable): Diagnosis = ExceptionDiagnosis.diagnose(t)
}
