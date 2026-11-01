package kei.status.engine

import android.webkit.WebView
import android.webkit.WebViewClient
import kei.status.webkit.Chrome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the report page's filter in a real browser.
 *
 * The string assertions in [ReportHtmlTest] cannot tell whether the script still calls `apply()`,
 * whether `remember()` writes a hash that `restore()` can read back, or whether Clear resets it.
 * Skipped when no browser is installed, like the WebView tests.
 */
class ReportHtmlInteractionTest {

    @Before
    fun requireBrowser() {
        assumeTrue("no Chromium available", runCatching { Chrome.port() }.isSuccess)
    }

    @After
    fun tearDown() = Chrome.stop()

    @Test
    fun `a shared hash restores, typing updates it, and clear resets it`() {
        val file = writePage()
        val view = open(file.toUri().toString() + "#status=fail")
        try {
            val restored = evaluate(
                view,
                "JSON.stringify({" +
                    "count: document.getElementById('count').textContent," +
                    " pressed: document.querySelector('.total[data-filter=fail]').getAttribute('aria-pressed')," +
                    " q: document.getElementById('q').value})",
            )
            assertEquals("1 / 2 sources", restored.getValue("count").jsonPrimitive.content)
            assertEquals("true", restored.getValue("pressed").jsonPrimitive.content)
            assertEquals("", restored.getValue("q").jsonPrimitive.content)

            val typed = evaluate(
                view,
                "var q = document.getElementById('q'); q.value = 'nomatch';" +
                    " q.dispatchEvent(new Event('input'));" +
                    " JSON.stringify({" +
                    "count: document.getElementById('count').textContent," +
                    " empty: !document.getElementById('no-matches').classList.contains('hidden')," +
                    " hash: location.hash})",
            )
            assertEquals("0 / 2 sources", typed.getValue("count").jsonPrimitive.content)
            assertTrue(typed.getValue("empty").jsonPrimitive.boolean)
            assertEquals("#status=fail&q=nomatch", typed.getValue("hash").jsonPrimitive.content)

            val cleared = evaluate(
                view,
                "document.getElementById('clear').click();" +
                    " JSON.stringify({" +
                    "count: document.getElementById('count').textContent," +
                    " q: document.getElementById('q').value," +
                    " empty: !document.getElementById('no-matches').classList.contains('hidden')," +
                    " hash: location.hash})",
            )
            assertEquals("2 / 2 sources", cleared.getValue("count").jsonPrimitive.content)
            assertEquals("", cleared.getValue("q").jsonPrimitive.content)
            assertFalse(cleared.getValue("empty").jsonPrimitive.boolean)
            assertEquals("", cleared.getValue("hash").jsonPrimitive.content)

            // Clear is a user action, so the live region confirms the full count again. Poll rather than
            // sleep a fixed 300ms: the timer runs in the browser, and a loaded runner may deliver
            // its callback later than the test thread expects.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var announced = ""
            while (announced.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50)
                announced = evaluate(view, "JSON.stringify({text: document.getElementById('announce').textContent})")
                    .getValue("text").jsonPrimitive.content
            }
            assertEquals("2 of 2 sources", announced)
        } finally {
            view.destroy()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `an unfiltered load announces nothing`() {
        val file = writePage()
        val view = open(file.toUri().toString())
        try {
            // Past the 300ms announcement delay: only an active filter may speak.
            Thread.sleep(450)
            val announce = evaluate(view, "JSON.stringify({text: document.getElementById('announce').textContent})")
            assertEquals("", announce.getValue("text").jsonPrimitive.content)
        } finally {
            view.destroy()
            Files.deleteIfExists(file)
        }
    }

    /**
     * A file URL, because `loadDataWithBaseURL` matches the document's request URL, which loses
     * the fragment before interception - and the fragment is what this test is about.
     */
    private fun open(url: String): WebView {
        val view = WebView(null)
        val loaded = CountDownLatch(1)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = loaded.countDown()
        }
        view.loadUrl(url)
        assertTrue("page never finished loading", loaded.await(30, TimeUnit.SECONDS))
        return view
    }

    private fun writePage(): Path {
        val file = Files.createTempFile("report", ".html")
        Files.writeString(file, pageHtml())
        return file
    }

    private fun pageHtml(): String = ReportHtml.site(
        listOf(
            RunReport(
                generatedAt = "2026-01-01T00:00:00Z",
                module = ":src:all:demo",
                sourceRoot = "/tmp/extensions-source",
                extensionCommit = "abc1234",
                sources = listOf(
                    SourceReport(1, "Fail Site", "en", "https://fail.test", Status.FAIL, emptyList()),
                    SourceReport(2, "Pass Site", "id", "https://pass.test", Status.PASS, emptyList()),
                ),
            ),
        ),
    )

    /** evaluateJavascript hands back a JSON-encoded value; these scripts return a JSON string. */
    private fun evaluate(view: WebView, script: String): JsonObject {
        val value = AtomicReference<String>()
        val done = CountDownLatch(1)
        view.evaluateJavascript(script) { result ->
            value.set(result)
            done.countDown()
        }
        assertTrue("evaluateJavascript never answered", done.await(30, TimeUnit.SECONDS))
        return Json.parseToJsonElement(Json.parseToJsonElement(value.get()).jsonPrimitive.content).jsonObject
    }
}