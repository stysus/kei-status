package kei.status.engine

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChecksTest {

    @Test
    fun `sentinel langs are not metadata violations`() {
        // `all` and `other` are declared by real extensions in the extension repo - Comic Fury's
        // comics are not in any language, HentaiHand advertises `other` for all 34 of its variants.
        // Flagging them would report a convention as a violation.
        listOf("all", "other", "en", "pt-BR", "zh").forEach { lang ->
            assertTrue("rejected $lang", Checks.metadata(1, "name", lang, "https://example.com").isEmpty())
        }

        // Everything the regex always rejected stays rejected.
        assertTrue(Checks.metadata(1, "name", "english", "https://example.com").isNotEmpty())
        assertTrue(Checks.metadata(1, "name", "other-x-y", "https://example.com").isNotEmpty())
        assertTrue(Checks.metadata(1, "name", "", "https://example.com").isNotEmpty())
    }

    @Test
    fun `manga without url or title is reported`() {
        val manga = SManga.create().apply {
            url = ""
            title = ""
        }

        val notes = Checks.mangaPage(MangasPage(listOf(manga), hasNextPage = false))

        assertTrue(notes.any { it.contains("blank url") })
        assertTrue(notes.any { it.contains("blank title") })
    }

    @Test
    fun `a page needs one usable url`() {
        // MangaDex keeps a server path in imageUrl and resolves it in getImageRequest, so a relative
        // URL is fine; what is not fine is no URL at all or one that cannot be an http URL.
        val accepted = listOf(
            Page(0, imageUrl = "https://example.com/1.jpg"),
            Page(0, imageUrl = "/data/1/1.png"),
        )
        accepted.forEach { assertTrue("rejected ${it.imageUrl}", Checks.pages(listOf(it)).isEmpty()) }

        val missing = Checks.pages(listOf(Page(0)))
        assertTrue("a page with no url at all passed: $missing", missing.any { it.contains("neither imageUrl nor url") })

        val malformed = Checks.pages(listOf(Page(0, imageUrl = "https://exa mple.com/1.jpg")))
        assertTrue("a malformed absolute url passed: $malformed", malformed.any { it.contains("not a valid http url") })
    }

    @Test
    fun `a missing platform class is unsupported`() {
        // Android plumbing and the JS engine are not emulated; a source needing them is out of
        // scope for the harness, not broken.
        listOf(
            "android/webkit/WebView",
            "android/os/Looper",
            "app/cash/quickjs/QuickJs",
        ).forEach { missing ->
            assertEquals(missing, Status.UNSUPPORTED, Checks.diagnose(NoClassDefFoundError(missing)).status)
        }
    }

    @Test
    fun `a missing harness class is a failure`() {
        // A keiyoushi class missing means our classpath is wrong, not that the source needs Android.
        val status = Checks.diagnose(NoClassDefFoundError("keiyoushi/utils/WebViewKt")).status

        assertEquals(Status.FAIL, status)
    }

    @Test
    fun `a missing platform member is unsupported`() {
        // The shim can provide android.net.Uri without providing Uri.encode; the JVM then fails at
        // link time with a message that names the member, not the class. It is the same gap as a
        // missing class, so blaming the extension for it would be wrong.
        val messages = listOf(
            NoSuchMethodError("'java.lang.String android.net.Uri.encode(java.lang.String)'"),
            NoSuchMethodError("'android.net.Uri\$Builder android.net.Uri.buildUpon()'"),
            NoSuchMethodError("'byte[] android.util.Base64.decode(byte[], int)'"),
            NoSuchFieldError("float android.graphics.Paint.density"),
            AbstractMethodError("androidx.preference.Preference\$OnPreferenceChangeListener.onPreferenceChange()"),
        )

        messages.forEach { error ->
            assertEquals(error.message, Status.UNSUPPORTED, Checks.diagnose(error).status)
        }

        val diagnosis = Checks.diagnose(NoSuchMethodError("'byte[] android.util.Base64.decode(byte[], int)'"))
        // HotSpot quotes the whole signature; the reported reason keeps the member, not the quotes.
        assertEquals(
            "requires an Android method or field the harness does not provide: android.util.Base64.decode",
            diagnosis.message,
        )
    }

    @Test
    fun `a missing member on a harness class is a failure`() {
        // Only the platform is emulated by us; a missing okhttp3 member means the shim links
        // against the wrong version, which is a real defect worth a FAIL.
        val status = Checks.diagnose(NoSuchMethodError("'okhttp3.Request\$Builder okhttp3.Request.newBuilder()'")).status

        assertEquals(Status.FAIL, status)
    }

    @Test
    fun `a challenge is blocked rather than failed`() {
        val status = Checks.diagnose(AntiBotChallengeException(503, "example.com", "Cloudflare")).status

        assertEquals(Status.BLOCKED, status)
    }

    @Test
    fun `an unsupported operation is skipped`() {
        val status = Checks.diagnose(UnsupportedOperationException()).status

        assertEquals(Status.SKIP, status)
    }

    @Test
    fun `an http error fails`() {
        val diagnosis = Checks.diagnose(HttpException(404))

        assertEquals(Status.FAIL, diagnosis.status)
        assertTrue(diagnosis.message.contains("404"))
    }

    @Test
    fun `an http 429 is throttling, not a failure`() {
        // The sweep walks one source per language against the same site, so a rate limit shows up
        // as dozens of identical failures that say nothing about the extension.
        val diagnosis = Checks.diagnose(HttpException(429))

        assertEquals(Status.THROTTLED, diagnosis.status)
        assertTrue(diagnosis.message.contains("429"))
        assertTrue(diagnosis.message.contains("rate-limiting"))
    }

    @Test
    fun `a throttled response reports the retry-after the site sent`() {
        val diagnosis = Checks.diagnose(HttpException(429, retryAfterSeconds = 30))

        assertEquals(Status.THROTTLED, diagnosis.status)
        assertTrue(diagnosis.message.contains("retry after 30s"))
    }

    @Test
    fun `throttled sits below blocked and above unsupported`() {
        // The order decides two things: which verdict a module rolls up to, and where the page's
        // filter chips put it. A throttle outranks PASS on purpose - the local checks still pass
        // while a site is refusing the sweep, and letting them win would report it as healthy.
        assertTrue(Status.FAIL.severity > Status.BLOCKED.severity)
        assertTrue(Status.BLOCKED.severity > Status.THROTTLED.severity)
        assertTrue(Status.THROTTLED.severity > Status.UNSUPPORTED.severity)
        assertTrue(Status.THROTTLED.severity > Status.PASS.severity)
        assertTrue(Status.UNSUPPORTED.severity > Status.WARN.severity)
        assertTrue(Status.WARN.severity > Status.PASS.severity)
        assertTrue(Status.PASS.severity > Status.SKIP.severity)
    }

    @Test
    fun `a wrapped cause is classified`() {
        val wrapped = RuntimeException("wrapped", AntiBotChallengeException(403, "example.com", "Cloudflare"))

        val status = Checks.diagnose(wrapped).status

        assertEquals(Status.BLOCKED, status)
    }

    @Test
    fun `a chain entry that only restates the reason is dropped`() {
        // The report already shows the reason; "java.io.IOException: <same text>" underneath it
        // says nothing new.
        val diagnosis = Checks.diagnose(IOException("Open in WebView to bypass site protection"))

        assertEquals("Open in WebView to bypass site protection", diagnosis.message)
        assertTrue(diagnosis.chain.toString(), diagnosis.chain.isEmpty())
    }

    @Test
    fun `a cause with its own message is kept`() {
        val diagnosis = Checks.diagnose(RuntimeException("wrapped", IOException("connection reset")))

        assertEquals("connection reset", diagnosis.message)
        assertEquals(listOf("java.lang.RuntimeException: wrapped"), diagnosis.chain)
    }

    @Test
    fun `a challenge keeps the class that raised it`() {
        // The reason is the harness's own wording here, so the exception still adds the provider.
        val diagnosis = Checks.diagnose(AntiBotChallengeException(503, "example.com", "Cloudflare"))

        assertEquals(Status.BLOCKED, diagnosis.status)
        assertTrue(diagnosis.message.contains("Cloudflare"))
        assertTrue(diagnosis.chain.single().startsWith("eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException"))
    }

    @Test
    fun `a chapter that needs an unlock is recognised`() {
        // The exact wording is the source's own; these are the messages in the extensions tree.
        listOf(
            "Locked chapter, unlock in WebView.",
            "Chapter locked. Open in WebView to unlock.",
            "Chapter is locked",
            "This chapter is locked. Use WebView to purchase it.",
            "Log in via WebView and unlock this chapter with coins to read.",
        ).forEach { message -> assertTrue(message, Checks.lockedChapter(message)) }

        // A chapter being locked is a property of the content. Failing to open the lock is not.
        listOf(
            null,
            "Timed out decrypting image links",
            "Could not unlock the decryption key",
            "HTTP 404",
        ).forEach { message -> assertFalse(message.toString(), Checks.lockedChapter(message)) }
    }

    @Test
    fun `a source that runs against your own server is recognised`() {
        // The defaults shipped by Komga, LANraragi, Stash, Bakkin Self-hosted and the two
        // customizable readers: there is no public server to probe.
        listOf(
            "http://127.0.0.1/",
            "https://127.0.0.1:25600",
            "http://127.0.0.1:3000",
            "http://localhost:9999",
            "http://0.0.0.0:8080",
            "http://[::1]:8080",
        ).forEach { url -> assertTrue(url, Checks.selfHosted(url)) }

        listOf(
            "https://comikey.com",
            "https://mangadex.org",
            // A LAN address is somebody's home server, not a published site, but it is not the
            // machine the harness runs on either; leave it to the source to fail honestly.
            "",
            "not a url",
        ).forEach { url -> assertFalse(url, Checks.selfHosted(url)) }
    }

    @Test
    fun `a missing class keeps the name that was missing`() {
        val diagnosis = Checks.diagnose(NoClassDefFoundError("android/webkit/WebView"))

        assertTrue(diagnosis.message.contains("android.webkit.WebView"))
        assertEquals(listOf("java.lang.NoClassDefFoundError: android/webkit/WebView"), diagnosis.chain)
    }
}
