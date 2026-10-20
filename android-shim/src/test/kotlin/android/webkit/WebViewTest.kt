package android.webkit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the WebView shim against a real Chromium.
 *
 * These assertions are the contract `runWebView` relies on: a document loads, page hooks fire on
 * the main thread, `evaluateJavascript` returns Android-shaped JSON, and a JS bridge can call back
 * into Kotlin. Skipped when no browser is installed.
 */
class WebViewTest {

    private val loaded = CountDownLatch(1)
    private val finished = CountDownLatch(1)

    @Before
    fun requireBrowser() {
        assumeTrue("no Chromium available", runCatching { kei.status.webkit.Chrome.port() }.isSuccess)
    }

    @After
    fun tearDown() {
        kei.status.webkit.Chrome.stop()
    }

    private fun webView(onFinished: (WebView) -> Unit = {}): WebView = WebView(null).apply {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loaded.countDown()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                finished.countDown()
                view?.let(onFinished)
            }
        }
    }

    @Test
    fun `loadDataWithBaseURL runs page scripts and reports lifecycle`() {
        val view = webView()
        view.loadDataWithBaseURL(
            "https://example.test/reader",
            """<html><body><h1 id="t">ok</h1></body></html>""",
            "text/html",
            "UTF-8",
            null,
        )

        assertTrue("onPageStarted never fired", loaded.await(30, TimeUnit.SECONDS))
        assertTrue("onPageFinished never fired", finished.await(30, TimeUnit.SECONDS))
        view.destroy()
    }

    @Test
    fun `evaluateJavascript returns Android-shaped values from the loaded document`() {
        // Both halves of the contract `runWebView` relies on: the callback receives Android-shaped
        // JSON, and the document runs at the base URL the source passed in.
        val string = AtomicReference<String>()
        val origin = AtomicReference<String>()
        val view = webView {
            it.evaluateJavascript("'hello'") { value -> string.set(value) }
            it.evaluateJavascript("location.origin") { value -> origin.set(value) }
        }
        view.loadDataWithBaseURL(
            "https://example.test/reader",
            "<html><body></body></html>",
            "text/html",
            "UTF-8",
            null,
        )

        assertTrue(finished.await(30, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while ((string.get() == null || origin.get() == null) && System.nanoTime() < deadline) Thread.sleep(50)

        assertEquals("\"hello\"", string.get())
        assertEquals("\"https://example.test\"", origin.get())
        view.destroy()
    }

    @Test
    fun `javascript interface receives calls from the page`() {
        val message = AtomicReference<String>()
        val received = CountDownLatch(1)
        val view = webView()

        view.addJavascriptInterface(
            object {
                @Suppress("unused")
                fun post(value: String) {
                    message.set(value)
                    received.countDown()
                }
            },
            "android",
        )

        view.loadDataWithBaseURL(
            "https://example.test/",
            """<html><body><script>window.android.post("from-page")</script></body></html>""",
            "text/html",
            "UTF-8",
            null,
        )

        assertTrue("bridge was never called", received.await(30, TimeUnit.SECONDS))
        assertEquals("from-page", message.get())
        view.destroy()
    }

    @Test
    fun `javascript interface receives calls from async page code`() {
        val message = AtomicReference<String>()
        val received = CountDownLatch(1)
        val view = webView()

        view.addJavascriptInterface(
            object {
                @Suppress("unused")
                fun post(value: String) {
                    message.set(value)
                    received.countDown()
                }
            },
            "android",
        )

        view.loadDataWithBaseURL(
            "https://example.test/",
            """
            <html><body><script>
              const original = Response.prototype.json;
              Response.prototype.json = function () {
                return original.call(this).then(data => {
                  if (data && data.chapterPages) window.android.post(JSON.stringify(data));
                  return data;
                });
              };
              fetch('data:application/json,{"chapterPages":[1,2,3]}').then(r => r.json());
            </script></body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null,
        )

        assertTrue("bridge was never called from async code", received.await(30, TimeUnit.SECONDS))
        assertTrue(message.get().contains("chapterPages"))
        view.destroy()
    }

    @Test
    fun `blockImages does not stall the load`() {
        // Blocking images is what keeps a page load cheap; if the blocked request kept the load
        // open, every WebView scenario would hang instead.
        val view = webView()
        view.settings.blockNetworkImage = true
        view.loadDataWithBaseURL(
            "https://example.test/",
            """<html><body><img src="https://example.test/missing.png"></body></html>""",
            "text/html",
            "UTF-8",
            null,
        )

        assertTrue("the load never finished with images blocked", finished.await(30, TimeUnit.SECONDS))
        view.destroy()
    }
}
