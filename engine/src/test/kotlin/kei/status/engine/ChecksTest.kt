package kei.status.engine

import android.content.ActivityNotFoundException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
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
    fun `blank urls are not counted as duplicates`() {
        // A blank url is already its own finding. Grouping blanks as "duplicates" renders a list
        // whose only element is the empty string - literally "duplicate page urls: []" - which
        // reads as duplicates found with none named.
        val notes = Checks.mangaPage(
            MangasPage(
                listOf(SManga.create().apply { url = "" }, SManga.create().apply { url = "" }),
                hasNextPage = false,
            ),
        )

        assertTrue(notes.none { it.startsWith("duplicate manga urls") })
    }

    @Test
    fun `blank chapter urls are not counted as duplicates`() {
        val notes = Checks.chapters(listOf(SChapter.create().apply { url = "" }, SChapter.create().apply { url = "" }))

        assertTrue(notes.none { it.startsWith("duplicate chapter urls") })
    }

    @Test
    fun `blank page image urls are not counted as duplicates`() {
        val notes = Checks.pages(listOf(Page(0), Page(1)))

        assertTrue(notes.none { it.startsWith("duplicate page urls") })
    }

    @Test
    fun `a repeated url among distinct ones is flagged as the page's identity`() {
        // MangaDex-family sources keep a unique server path in imageUrl, so their pages stay checked
        // on imageUrl; a url-only source that repeats a url among distinct ones is the reader showing
        // one image twice. Two identical pages with no other url are indistinguishable from the
        // index-disambiguated pattern below and pass silently.
        val notes = Checks.pages(
            listOf(Page(0, url = "/data/1/1.png"), Page(1, url = "/data/1/1.png"), Page(2, url = "/data/1/2.png")),
        )

        assertTrue("passed silently: $notes", notes.any { it.startsWith("duplicate page urls") })
    }

    @Test
    fun `duplicate notes name the same urls regardless of list order`() {
        // The store compares note text, so a site that returns its list in a different order must
        // not change the note and churn the file every night.
        val reversed = Checks.mangaPage(
            MangasPage(
                listOf(SManga.create().apply { url = "/manga/c" }, SManga.create().apply { url = "/manga/a" },
                    SManga.create().apply { url = "/manga/b" }, SManga.create().apply { url = "/manga/a" }),
                hasNextPage = false,
            ),
        )
        val rotated = Checks.mangaPage(
            MangasPage(
                listOf(SManga.create().apply { url = "/manga/a" }, SManga.create().apply { url = "/manga/a" },
                    SManga.create().apply { url = "/manga/b" }, SManga.create().apply { url = "/manga/c" }),
                hasNextPage = false,
            ),
        )

        assertEquals(reversed, rotated)
    }

    @Test
    fun `a single url for the whole page list is index-disambiguated, not a duplicate`() {
        // Comix hands every page of a chapter the same url and resolves getImageUrl by page.index;
        // the reader shows a different image per page, so the repeated url is not a duplicate.
        val notes = Checks.pages(List(3) { Page(it, url = "/c/1") })

        assertTrue("flagged the index-disambiguated pattern: $notes", notes.none { it.startsWith("duplicate page urls") })
    }

    @Test
    fun `an empty manga list earns one note`() {
        // popular, latest and search all route through here; the note names no scenario so it reads
        // the same whichever one produced it.
        val notes = Checks.mangaPage(MangasPage(emptyList(), hasNextPage = false))

        assertEquals(listOf("returned an empty list"), notes)
    }

    @Test
    fun `blank manga titles are warned, not failed`() {
        // A blank title is a finding on popular/latest - and it is why search skips instead of
        // asking the site for it: an empty query is the harness probing badly.
        val notes = Checks.mangaPage(MangasPage(listOf(SManga.create().apply { title = "" }), hasNextPage = false))

        assertTrue(notes.any { it.startsWith("manga[0] has a blank title") })
    }

    @Test
    fun `real duplicates are still reported`() {
        val notes = Checks.mangaPage(
            MangasPage(
                listOf(SManga.create().apply { url = "/manga/a" }, SManga.create().apply { url = "/manga/a" }),
                hasNextPage = false,
            ),
        )

        assertTrue(notes.any { it.startsWith("duplicate manga urls") })
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
    fun `a native library the host cannot load is unsupported`() {
        // The QuickJS jar carries libquickjs.so, which links against libc++; on a host without it
        // the load fails with UnsatisfiedLinkError naming the runtime, not the extension.
        val status = Checks.diagnose(
            UnsatisfiedLinkError(
                "/tmp/quickjs11888426660758811896.tmp: libc++.so.1: cannot open shared object file: " +
                    "No such file or directory",
            ),
        ).status

        assertEquals(Status.UNSUPPORTED, status)
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
    fun `a webview session that never finished is blocked rather than failed`() {
        // keiyoushi.utils.WebViewTimeoutException is not on the engine class path, so the chain is
        // matched on the message the core util throws - the same text extensions re-throw in their
        // own words ("Timed out waiting for WebView", Comix). The source needs a browser session
        // that finished; that is a block, not a broken extension.
        val timeout = Exception("Timed out waiting for WebView after 15s")

        val diagnosis = Checks.diagnose(timeout)

        assertEquals(Status.BLOCKED, diagnosis.status)
        assertTrue(diagnosis.message.contains("WebView"))
    }

    @Test
    fun `a site verification the sweep cannot pass is blocked rather than failed`() {
        // The pam and templescan themes throw this exact text when the site's JS verification
        // stays up - the extension works in the app, where a real WebView passes it.
        val verification = Exception("Open in WebView to pass the site's verification")

        assertEquals(Status.BLOCKED, Checks.diagnose(verification).status)
    }

    @Test
    fun `a login demand in any language is blocked rather than failed`() {
        // Real sweep failures, verbatim. Each source is working: it refuses the anonymous access
        // the sweep cannot lift, which is a block, not a broken extension.
        val demands = listOf(
            "Open webview to refresh token", // hdoujin
            "Open chapter in WebView, then try again (token not found).", // comikey
            "V2PH Session expired. Please log in via WebView to view more than 20 images.",
            "Для просмотра контента необходима авторизация через WebView или включите автоматическую авторизацию в настройках расширения", // mintmanga family
            "Okumak için WebView üzerinden giriş yapın", // lavinia fansub family
            "Faça login no WebView ou insira email e senha nas configurações e tente novamente.", // kuromangas
            "Configure e-mail e senha nas preferências da extensão", // mediocre toons
            "Nguồn này cần đăng nhập bằng webview để xem", // yurigarden
            "請在擴充套件設定中輸入帳號與密碼，或在 WebView 中登入", // noyacg
            "请先在插件设置中登录", // hanabimanga
            "请先在 WebView 中登录", // hikarinagi
            "请在扩展设置界面输入用户名和密码", // picacomic - password without "log in"
            "Autentique-se através da WebView e tente novamente.", // pink sea unicorn
            "Acesso negado. É necessário estar autenticado e ter VIP ativo para acessar este conteúdo.", // verdinha
            "Oturum açmanız, WebView'ı açmanız ve oturum açmanız gerekir", // hattori manga
            "يرجى تسجيل الدخول من إعدادات الإضافة", // arabic
            "Login wajib untuk membuka konten ini. Buka di WebView dan login dengan Google.", // comicaso
            "Username or password cannot be empty.\nGo to Extensions > Details to input your credentials.", // madokami
        )

        for (message in demands) {
            assertEquals(message, Status.BLOCKED, Checks.diagnose(Exception(message)).status)
        }
    }

    @Test
    fun `the login verdict names the credentials the sweep cannot give`() {
        val diagnosis = Checks.diagnose(Exception("Open webview to refresh token"))

        assertEquals(
            "the source requires login or credentials the sweep cannot provide; the source is not broken",
            diagnosis.message,
        )
    }

    @Test
    fun `a geo restriction is blocked rather than failed`() {
        // Real sweep failures, verbatim. The sites answer from outside Japan with a refusal, the
        // extension is working as designed.
        val refusals = listOf(
            "Your country is not supported.", // DMM (and FANZA)
            "This service is only available in Japan.", // FOD, Music Book Japan
            "This service can only be used from Japan.", // Line Manga, U-NEXT
        )

        for (message in refusals) {
            assertEquals(message, Status.BLOCKED, Checks.diagnose(Exception(message)).status)
        }
    }

    @Test
    fun `the geo verdict names the region the sweep runs from`() {
        val diagnosis = Checks.diagnose(Exception("Your country is not supported."))

        assertEquals(
            "the site restricts by region and the sweep runs outside the allowed area; the source is not broken",
            diagnosis.message,
        )
    }

    @Test
    fun `a chapter that is merely unavailable is not a geo restriction`() {
        // Goraku Web serves this for a single removed chapter; the rest of the source passes.
        assertEquals(Status.FAIL, Checks.diagnose(Exception("This chapter is not available.")).status)
    }

    @Test
    fun `a parse error quoting a login field is not a login demand`() {
        // kotlinx quotes the offending JSON in its message; a DTO that grew a `loginUrl` field is
        // a parse drift to fix, and must keep failing. Word boundaries keep "login" from matching
        // inside "loginUrl".
        val parseError = Exception(
            "Unexpected JSON token at offset 4: Unknown key 'loginUrl' at path \$\n" +
                "JSON input: {\"loginUrl\":\"abcd\"}",
        )

        assertEquals(Status.FAIL, Checks.diagnose(parseError).status)
    }

    @Test
    fun `ordinary words that merely contain log in or sign in are not login demands`() {
        // "log in" is a substring of "catalog in" and "sign in" of "designed in"; only the whole
        // words count, and a bare HTTP status stays a failure.
        assertEquals(Status.FAIL, Checks.diagnose(Exception("catalog in progress: 0 entries")).status)
        assertEquals(Status.FAIL, Checks.diagnose(Exception("designed in Tokyo, shipped worldwide")).status)
        assertEquals(Status.FAIL, Checks.diagnose(HttpException(404)).status)
    }

    @Test
    fun `a purchase wall that demands login still skips as a locked chapter`() {
        // diagnose() reads the thrown exception, the pages check reads the recorded message; the
        // runner consults lockedChapter last, so the skip wins over this block.
        val message = "Log in via WebView and purchase this chapter to read."

        assertEquals(Status.BLOCKED, Checks.diagnose(Exception(message)).status)
        assertTrue(Checks.lockedChapter(message))
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
    fun `a message-less exception names the extension frame that threw it`() {
        // Kotlin's `!!` throws a NullPointerException with no message, and the recorded state used
        // to say just "NullPointerException" - unusable for triage, since the same failure needs a
        // local re-run with tracing to locate. The extension's own stack frame is the location.
        val npe = NullPointerException().apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "eu.kanade.tachiyomi.extension.ja.piccoma.Piccoma\$fetchMangaUpdate\$2\$details\$1",
                    "invokeSuspend",
                    "Piccoma.kt",
                    104,
                ),
                StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 100),
            )
        }

        val diagnosis = Checks.diagnose(npe)

        assertEquals(
            "NullPointerException at Piccoma\$fetchMangaUpdate\$2\$details\$1.invokeSuspend(Piccoma.kt:104)",
            diagnosis.message,
        )
    }

    @Test
    fun `a message-less exception with no extension frame stays plain`() {
        // An NPE raised by harness or platform code has no frame worth naming; the class name is
        // still the best one-line reason there is.
        val npe = NullPointerException().apply {
            stackTrace = arrayOf(
                StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 100),
            )
        }

        assertEquals("NullPointerException", Checks.diagnose(npe).message)
    }

    @Test
    fun `an exception with a message is never augmented with a frame`() {
        val npe = NullPointerException("Cannot invoke String.length() because x is null").apply {
            stackTrace = arrayOf(
                StackTraceElement("eu.kanade.tachiyomi.extension.ja.piccoma.Piccoma", "getPopularManga", "Piccoma.kt", 90),
            )
        }

        assertEquals("Cannot invoke String.length() because x is null", Checks.diagnose(npe).message)
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
            // The same refusal without the word "lock": the Kadokawa family and the login-first
            // ones the 2026-10-24 sweep surfaced.
            "This chapter is either unavailable or must be purchased.",
            "Log in via WebView and purchase this chapter to read.",
            "Log in via WebView and rent or purchase this chapter to read.",
            "Log in via WebView and purchase this product to read.",
            "Log in via WebView and get premium or purchase this chapter to read.",
        ).forEach { message -> assertTrue(message, Checks.lockedChapter(message)) }

        // A chapter being locked is a property of the content. Failing to open the lock is not.
        listOf(
            null,
            "Timed out decrypting image links",
            "Could not unlock the decryption key",
            "HTTP 404",
        ).forEach { message -> assertFalse(message.toString(), Checks.lockedChapter(message)) }

        // A parse error quoting a paywall-adjacent field name is not a paywall.
        assertFalse(
            Checks.lockedChapter(
                "Unexpected JSON token at offset 0: Unknown key 'purchaseUrl' at path: $.data",
            ),
        )
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

    @Test
    fun `a captcha that demands a human is blocked`() {
        val diagnosis = Checks.diagnose(
            IllegalStateException("Captcha needs interaction, but the app isn't in the foreground"),
        )

        // TurnstileHelper runs the challenge headless and only rejects with this when Cloudflare
        // decided the puzzle needs a human - which no sweep can provide, though any device with a
        // person at it could pass it, so the source is not broken.
        assertEquals(Status.BLOCKED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("captcha needs a human"))
    }

    @Test
    fun `a source that opens its own verification activity is blocked`() {
        // MangaLivre launches its in-app ReaderVerificationActivity (a WebView captcha step) via
        // startActivity. The harness refuses every intent, and on a real device this exception
        // cannot happen because the activity ships inside the APK - so it is a demand the sweep
        // cannot meet, not a broken source.
        val diagnosis = Checks.diagnose(
            ActivityNotFoundException("no activity to handle ComponentInfo{br.net.mangalivre/ReaderVerificationActivity}"),
        )

        assertEquals(Status.BLOCKED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("verification or captcha step"))
        assertEquals(
            listOf("android.content.ActivityNotFoundException: " +
                "no activity to handle ComponentInfo{br.net.mangalivre/ReaderVerificationActivity}"),
            diagnosis.chain,
        )
    }

    // region browser harness failures → UNSUPPORTED

    @Test
    fun `a browser socket failure is unsupported, not fail`() {
        val cause = java.io.IOException("Connection reset by peer")
        val diagnosis = Checks.diagnose(
            kei.status.webkit.ChromeUnavailableException("DevTools socket to page abc123 failed: Connection reset by peer", cause),
        )

        assertEquals(Status.UNSUPPORTED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("browser session failed"))
    }

    @Test
    fun `a closed devtools session is unsupported`() {
        val diagnosis = Checks.diagnose(
            kei.status.webkit.ChromeUnavailableException("DevTools session is closed"),
        )

        assertEquals(Status.UNSUPPORTED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("browser session failed"))
    }

    @Test
    fun `a browser failure wrapped by reflection is still unsupported`() {
        val chrome = kei.status.webkit.ChromeUnavailableException("DevTools session is closed")
        val wrapped = java.lang.reflect.InvocationTargetException(chrome)
        val diagnosis = Checks.diagnose(wrapped)

        assertEquals(Status.UNSUPPORTED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("browser session failed"))
    }

    @Test
    fun `a failed target creation is unsupported`() {
        // Chromium died between port()'s health check and the HTTP request — ConnectException
        // wrapped by newPage() as ChromeUnavailableException.
        val cause = java.net.ConnectException("Connection refused")
        val diagnosis = Checks.diagnose(
            kei.status.webkit.ChromeUnavailableException("could not create a DevTools target on port 9222: Connection refused", cause),
        )

        assertEquals(Status.UNSUPPORTED, diagnosis.status)
        assertTrue(diagnosis.message, diagnosis.message.contains("browser session failed"))
    }

    // endregion
}
