package android.webkit

import kei.status.webkit.ChallengeSolver
import kei.status.webkit.Chrome
import kei.status.webkit.Page
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Drives the Cloudflare-challenge clicker against a real Chromium.
 *
 * The fixtures reproduce two things the live widget does and that the first version of this code got
 * wrong: the checkbox is an iframe inside a **closed** shadow root, so page JavaScript cannot see it
 * at all, and the widget is only clickable once Turnstile has announced `interactiveBegin`, which
 * happens after it is already painted. Skipped when no browser is installed.
 */
class ChallengeSolverTest {

    @Before
    fun requireBrowser() {
        assumeTrue("no Chromium available", runCatching { Chrome.port() }.isSuccess)
    }

    @After
    fun tearDown() = Chrome.stop()

    // The fixture's widget, in CSS pixels. Assertions are expressed relative to this box so they
    // describe "the checkbox was hit" rather than one particular arithmetic result.
    private val widgetLeft = 100.0
    private val widgetTop = 200.0
    private val widgetWidth = 300.0
    private val widgetHeight = 65.0

    private val widgetRight get() = widgetLeft + widgetWidth
    private val widgetBottom get() = widgetTop + widgetHeight

    private fun style() =
        "position:absolute;left:${widgetLeft}px;top:${widgetTop}px;" +
            "width:${widgetWidth}px;height:${widgetHeight}px"

    /**
     * A challenge page whose widget records every click.
     *
     * The listener is on the document in the capture phase, because a click on a widget inside a
     * closed shadow root is retargeted on its way up and never reaches a listener bound to the
     * widget itself.
     */
    private fun fixture(body: String) = """
        <html><head><title>Just a moment...</title></head>
        <body style="margin:0">
          $body
          <script>
            window.__clicks = [];
            document.addEventListener('click', function (event) {
              window.__clicks.push({ x: event.clientX, y: event.clientY });
            }, true);
          </script>
        </body></html>
    """.trimIndent()

    private fun evaluate(page: Page, expression: String): String? = runCatching {
        page.call(
            "Runtime.evaluate",
            JsonObject(
                mapOf(
                    "expression" to JsonPrimitive(expression),
                    "returnByValue" to JsonPrimitive(true),
                ),
            ),
        )["result"]?.jsonObject?.get("value")?.toString()
    }.getOrNull()

    private fun value(page: Page, expression: String): String? = evaluate(page, expression)?.trim('"')

    /** Installs the readiness watcher the way the solver does, into an already loaded document. */
    private fun armWatcher(page: Page) {
        evaluate(page, ChallengeSolver.watcherScript)
    }

    /**
     * Replays Turnstile's readiness announcement. `MessageEvent`'s origin is settable, so the
     * watcher sees exactly the message the live widget sends.
     */
    private fun announceInteractive(page: Page, widgetId: String = "test1") {
        evaluate(
            page,
            """
            window.dispatchEvent(new MessageEvent('message', {
              origin: 'https://challenges.cloudflare.com',
              data: JSON.stringify({ source: 'cloudflare-challenge', widgetId: '$widgetId', event: 'interactiveBegin' })
            }));
            """.trimIndent(),
        )
    }

    /** Writes [html] into a blank page, which is the cheapest way to serve a fixture. */
    private fun pageWith(html: String): Page {
        val page = Chrome.newPage()
        page.call("Page.enable")
        page.call("DOM.enable")
        page.call(
            "Runtime.evaluate",
            JsonObject(
                mapOf(
                    "expression" to JsonPrimitive(
                        "document.open();document.write(${JsonPrimitive(html)});document.close()",
                    ),
                ),
            ),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (value(page, "document.readyState") != "complete" && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        armWatcher(page)
        return page
    }

    private fun clickCount(page: Page): Int? = value(page, "String(window.__clicks.length)")?.toIntOrNull()

    /**
     * Asserts a click landed on the checkbox: inside the widget, near its left edge, and vertically
     * centred. The tolerances are fractions of the widget, so they hold for any widget size.
     */
    private fun assertHitsCheckbox(x: Double, y: Double, label: String) {
        assertTrue("$label: x=$x outside the widget [$widgetLeft,$widgetRight]", x in widgetLeft..widgetRight)
        assertTrue("$label: y=$y outside the widget [$widgetTop,$widgetBottom]", y in widgetTop..widgetBottom)
        assertTrue(
            "$label: x=$x is not in the checkbox's left-hand strip",
            x - widgetLeft <= widgetWidth * 0.2,
        )
        assertTrue(
            "$label: y=$y is not vertically centred",
            abs(y - (widgetTop + widgetHeight / 2)) <= widgetHeight * 0.25,
        )
    }

    private fun assertClickedCheckbox(page: Page, label: String) {
        assertEquals("$label: click count", 1, clickCount(page))
        val x = value(page, "String(window.__clicks[0].x)")?.toDouble()
        val y = value(page, "String(window.__clicks[0].y)")?.toDouble()
        assertNotNull("$label: no click recorded", x)
        assertHitsCheckbox(requireNotNull(x), requireNotNull(y), label)
    }

    @Test
    fun `no click point is offered before the widget accepts input`() {
        // A painted widget is not a clickable one: clicking now is discarded by Turnstile and the
        // challenge stays up, which is exactly the bug this gate fixes.
        val page = pageWith(fixture("""<div class="cf-turnstile" style="${style()}"></div>"""))
        try {
            val before = ChallengeSolver.probe(page)
            assertTrue("challenge not detected", before.isChallenge)
            assertTrue("document not reported ready", before.ready)
            assertTrue("widget should not be interactive yet", !before.interactive)
            assertNull("a click was offered before the widget accepted input", before.clickAt)

            announceInteractive(page)

            val after = ChallengeSolver.probe(page)
            assertTrue("interactiveBegin was ignored", after.interactive)
            val point = requireNotNull(after.clickAt) { "no click point after interactiveBegin" }
            assertHitsCheckbox(point.x, point.y, "probe")
        } finally {
            page.close()
        }
    }

    @Test
    fun `probe reports the checkbox and clicks it once`() {
        val page = pageWith(fixture("""<div class="cf-turnstile" style="${style()}"></div>"""))
        try {
            announceInteractive(page)

            val probe = ChallengeSolver.probe(page)
            assertTrue("challenge not detected", probe.isChallenge)
            assertNotNull("no widget key", probe.widgetKey)

            val point = requireNotNull(probe.clickAt) { "no click point for a rendered widget" }
            assertHitsCheckbox(point.x, point.y, "probe")

            ChallengeSolver.click(page, point)
            Thread.sleep(300)
            assertClickedCheckbox(page, "plain widget")
        } finally {
            page.close()
        }
    }

    @Test
    fun `widget inside a closed shadow root is found and clicked`() {
        // This is the layout Cloudflare actually serves: the widget is an iframe inside a closed
        // shadow root, so `document.querySelectorAll('iframe')` finds nothing. The iframe records
        // its own clicks and reports them to the parent, because a click inside an iframe never
        // propagates to the parent document.
        val page = pageWith(
            fixture(
                """
                <div id="host" style="position:absolute;left:${widgetLeft}px;top:${widgetTop}px"></div>
                <script>
                  const root = document.getElementById('host').attachShadow({ mode: 'closed' });
                  const box = document.createElement('iframe');
                  box.id = 'cf-chl-widget-test1';
                  box.style.cssText = 'width:${widgetWidth}px;height:${widgetHeight}px;border:0';
                  box.srcdoc = `
                    <body style="margin:0">
                      <script>
                        document.addEventListener('click', function (event) {
                          const host = parent.document.getElementById('host').getBoundingClientRect();
                          parent.__clicks.push({ x: event.clientX + host.x, y: event.clientY + host.y });
                        }, true);
                      </` + `script>
                    </body>`;
                  root.appendChild(box);
                </script>
                """.trimIndent(),
            ),
        )
        try {
            assertEquals(
                "fixture should hide its widget from page JavaScript",
                "0",
                value(page, "String(document.querySelectorAll('iframe').length)"),
            )

            announceInteractive(page)

            val probe = ChallengeSolver.probe(page)
            assertTrue("challenge not detected", probe.isChallenge)
            assertEquals("widget key should come from the shadow widget", "cf-chl-widget-test1", probe.widgetKey)

            val point = requireNotNull(probe.clickAt) { "closed-shadow widget was not found" }
            assertHitsCheckbox(point.x, point.y, "shadow probe")

            ChallengeSolver.click(page, point)
            Thread.sleep(500)
            assertClickedCheckbox(page, "closed-shadow widget")
        } finally {
            page.close()
        }
    }

    @Test
    fun `probing twice reports the same widget so callers can dedupe`() {
        val page = pageWith(
            fixture(
                """
                <div id="host" style="position:absolute;left:${widgetLeft}px;top:${widgetTop}px"></div>
                <script>
                  const root = document.getElementById('host').attachShadow({ mode: 'closed' });
                  const box = document.createElement('iframe');
                  box.id = 'cf-chl-widget-stable';
                  box.style.cssText = 'width:${widgetWidth}px;height:${widgetHeight}px;border:0';
                  root.appendChild(box);
                </script>
                """.trimIndent(),
            ),
        )
        try {
            announceInteractive(page)
            val first = ChallengeSolver.probe(page).widgetKey
            val second = ChallengeSolver.probe(page).widgetKey
            assertNotNull("no widget key", first)
            assertEquals("widget key changed between probes, so a caller cannot dedupe clicks", first, second)
        } finally {
            page.close()
        }
    }

    @Test
    fun `probe ignores ordinary pages`() {
        val page = pageWith("<html><head><title>A manga site</title></head><body><h1>hi</h1></body></html>")
        try {
            val probe = ChallengeSolver.probe(page)
            assertTrue("ordinary page was treated as a challenge", !probe.isChallenge)
            assertNull(probe.clickAt)
        } finally {
            page.close()
        }
    }

    @Test
    fun `webview clicks the widget once it accepts input`() {
        val view = WebView(null)
        view.loadDataWithBaseURL(
            "https://example.test/",
            fixture("""<div class="cf-turnstile" style="${style()}"></div>"""),
            "text/html",
            "UTF-8",
            null,
        )

        // The WebView arms the watcher for every document, so the page's own announcement is what
        // releases the click.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val ready = AtomicReference<String>()
            view.evaluateJavascript("String(document.readyState)") { ready.set(it) }
            if (ready.get()?.trim('"') == "complete") break
            Thread.sleep(100)
        }

        val clicks = AtomicReference<String>()
        view.evaluateJavascript("String(window.__clicks.length)") { clicks.set(it) }
        Thread.sleep(1500)
        assertEquals("the widget was clicked before it accepted input", "0", clicks.get()?.trim('"'))

        view.evaluateJavascript(
            """
            window.dispatchEvent(new MessageEvent('message', {
              origin: 'https://challenges.cloudflare.com',
              data: JSON.stringify({ source: 'cloudflare-challenge', widgetId: 'wv1', event: 'interactiveBegin' })
            }));
            """.trimIndent(),
        ) { }

        val clickDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (clicks.get()?.trim('"') != "1" && System.nanoTime() < clickDeadline) {
            view.evaluateJavascript("String(window.__clicks.length)") { clicks.set(it) }
            Thread.sleep(250)
        }

        assertEquals("the challenge widget was never clicked", "1", clicks.get()?.trim('"'))
        view.destroy()
    }
}
