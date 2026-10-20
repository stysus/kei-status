package kei.status.webkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * False when KEI_STATUS_NO_CF_SOLVE is set, which restores plain detection: a challenge is then
 * reported as a block instead of being clicked.
 */
internal val autoSolveEnabled: Boolean = System.getenv("KEI_STATUS_NO_CF_SOLVE") == null

/** A point in the viewport's CSS pixel space, which is what `Input.dispatchMouseEvent` expects. */
internal data class ChallengePoint(val x: Double, val y: Double)

/**
 * What a page currently shows.
 *
 * [interactive] is true once the widget has announced that it accepts input. A rendered widget is
 * not a clickable widget: the checkbox is painted about a second before Turnstile finishes booting,
 * and a click before that is silently discarded, leaving the challenge up forever.
 *
 * [widgetKey] identifies the widget a click point belongs to. Cloudflare gives every widget a fresh
 * id, so the key changes when the provider replaces a rejected widget, and the caller can click each
 * widget once without ever clicking the same one twice.
 */
internal data class ChallengeProbe(
    val isChallenge: Boolean,
    val ready: Boolean,
    val interactive: Boolean,
    val clickAt: ChallengePoint?,
    val widgetKey: String?,
)

/**
 * Locates and clicks Cloudflare's challenge checkbox.
 *
 * Two facts about the live widget shape this code:
 *
 * 1. The checkbox lives in a Turnstile iframe inside a **closed** shadow root, so page JavaScript
 *    cannot see it: `document.querySelectorAll('iframe')` returns nothing while the checkbox is
 *    plainly on screen. It is therefore located through the DevTools DOM domain, which pierces
 *    shadow roots.
 * 2. The widget is only clickable after it has booted, which happens well after `readyState` is
 *    `complete` and even after its geometry is correct. Turnstile announces that moment with a
 *    `postMessage` event named `interactiveBegin`, so the click is gated on that event rather than
 *    on a timer: no guessing, and no clicking into a half-built widget.
 *
 * The click itself is a real mouse event - the same interaction a person performs in the app's
 * WebView. Nothing is forged: if Cloudflare rejects the browser the interstitial stays up and the
 * caller reports a block.
 */
internal object ChallengeSolver {

    /** Ids Cloudflare gives the Turnstile widget, inside or outside a shadow root. */
    private const val TURNSTILE_ID_PREFIX = "cf-chl-widget"

    private const val TURNSTILE_ORIGIN = "https://challenges.cloudflare.com"

    /**
     * Records Turnstile's lifecycle messages in the page.
     *
     * Turnstile reports its own readiness to the embedding page: `init`, `translationInit`, then
     * `interactiveBegin` when the widget starts accepting input, and `interactiveEnd` when it stops.
     * Listening for those events is the only signal that means "click now" rather than "wait
     * approximately this long".
     */
    internal val watcherScript: String = """
        (function () {
          if (window.__keiCf) return;
          var state = { interactive: false, widgetId: null, event: null, error: null };
          window.__keiCf = state;
          window.addEventListener('message', function (e) {
            if (e.origin !== '$TURNSTILE_ORIGIN') return;
            var data = e.data;
            if (typeof data === 'string') {
              try { data = JSON.parse(data); } catch (_) { return; }
            }
            if (!data || data.source !== 'cloudflare-challenge') return;
            state.event = data.event;
            if (data.widgetId) state.widgetId = data.widgetId;
            if (data.event === 'interactiveBegin') state.interactive = true;
            if (data.event === 'interactiveEnd') state.interactive = false;
            if (data.event === 'error' || data.event === 'fail') state.error = data.code || true;
          });
        })();
    """.trimIndent()

    /**
     * Installs the readiness watcher for every document the page loads.
     *
     * Registering it as a new-document script means it runs before the challenge's own script, so
     * `interactiveBegin` cannot be missed however long the caller takes to start polling.
     */
    fun installWatcher(page: Page) {
        page.send(
            "Page.addScriptToEvaluateOnNewDocument",
            JsonObject(mapOf("source" to JsonPrimitive(watcherScript))),
        )
    }

    /**
     * Detects the interstitial, finds the widget, and reports whether it accepts input.
     *
     * The script stage handles a widget that page JavaScript can reach. The second stage covers the
     * closed-shadow layout that Cloudflare actually serves.
     */
    private fun detectScript(): String = """
        (function () {
          const WIDGET = 'iframe[src*="challenges.cloudflare.com"], .cf-turnstile';
          const title = document.title || '';
          const isChallenge =
            /just a moment|attention required|checking your browser|verifying you are human/i.test(title) ||
            !!document.querySelector('#challenge-form, #challenge-stage, ' + WIDGET);
          if (!isChallenge) return { challenge: false, ready: true };

          const ready = document.readyState === 'complete';
          if (!ready) return { challenge: true, ready: false };

          const state = window.__keiCf || {};
          const interactive = !!state.interactive;
          if (!interactive) return { challenge: true, ready: true, interactive: false };

          const el = [...document.querySelectorAll(WIDGET)].find(el => {
            const r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
          });
          if (!el) return { challenge: true, ready: true, interactive: true };

          el.scrollIntoView({ block: 'center', inline: 'center' });
          const r = el.getBoundingClientRect();
          const key = el.id || el.getAttribute('src') || 'cf-widget';
          return { challenge: true, ready: true, interactive: true, key: key, x: r.x, y: r.y, height: r.height };
        })()
    """.trimIndent()

    private fun params(expression: String) = JsonObject(
        mapOf(
            "expression" to JsonPrimitive(expression),
            "returnByValue" to JsonPrimitive(true),
        ),
    )

    /** Blocking probe. Never call from the main thread: the browser round trips can stall. */
    fun probe(page: Page): ChallengeProbe =
        parse(page, runCatching { page.call("Runtime.evaluate", params(detectScript())) }.getOrNull())

    /** Non-blocking probe, for callers that own a thread the browser must not stall. */
    fun probeAsync(page: Page, callback: (ChallengeProbe) -> Unit) {
        page.send("Runtime.evaluate", params(detectScript())).whenComplete { result, error ->
            if (error != null) {
                callback(ChallengeProbe(false, false, false, null, null))
                return@whenComplete
            }
            callback(parse(page, result))
        }
    }

    private fun parse(page: Page, result: JsonObject?): ChallengeProbe {
        val value = result?.get("result")?.jsonObject?.get("value") as? JsonObject
            ?: return ChallengeProbe(isChallenge = false, ready = false, interactive = false, clickAt = null, widgetKey = null)

        val isChallenge = value.boolean("challenge")
        if (!isChallenge) return ChallengeProbe(false, true, false, null, null)

        val ready = value.boolean("ready")
        if (!ready) return ChallengeProbe(true, false, false, null, null)

        if (!value.boolean("interactive")) return ChallengeProbe(true, true, false, null, null)

        val key = value["key"]?.jsonPrimitive?.content
        val x = value["x"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val y = value["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val height = value["height"]?.jsonPrimitive?.content?.toDoubleOrNull()

        if (key != null && x != null && y != null && height != null) {
            // The widget's checkbox sits at the left edge, vertically centred.
            return ChallengeProbe(true, true, true, ChallengePoint(x + CHECKBOX_OFFSET_X, y + height / 2), key)
        }

        // The widget is invisible to page JavaScript, which is the usual case for the managed
        // challenge: it renders inside a closed shadow root.
        return shadowWidget(page)
    }

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.content == "true"

    /**
     * Finds a Turnstile widget through the DevTools DOM domain.
     *
     * `DOM.getDocument(pierce: true)` descends into shadow roots, including closed ones, and
     * `DOM.getContentQuads` returns viewport coordinates for the click.
     */
    private fun shadowWidget(page: Page): ChallengeProbe {
        val document = runCatching {
            page.call(
                "DOM.getDocument",
                JsonObject(mapOf("depth" to JsonPrimitive(-1), "pierce" to JsonPrimitive(true))),
            )
        }.getOrNull() ?: return ChallengeProbe(true, true, true, null, null)

        val widget = findWidget(document.getValue("root"))
            ?: return ChallengeProbe(true, true, true, null, null)

        val quads = runCatching {
            page.call("DOM.getContentQuads", JsonObject(mapOf("nodeId" to JsonPrimitive(widget.first))))
        }.getOrNull() ?: return ChallengeProbe(true, true, true, null, null)

        val quad = (quads["quads"] as? JsonArray)?.firstOrNull() as? JsonArray
            ?: return ChallengeProbe(true, true, true, null, null)
        val values = quad.mapNotNull { it.jsonPrimitive.content.toDoubleOrNull() }
        if (values.size < 8) return ChallengeProbe(true, true, true, null, null)

        val xs = listOf(values[0], values[2], values[4], values[6])
        val ys = listOf(values[1], values[3], values[5], values[7])
        val point = ChallengePoint(xs.min() + CHECKBOX_OFFSET_X, (ys.min() + ys.max()) / 2)

        return ChallengeProbe(true, true, true, point, widget.second)
    }

    /** Depth-first search for the widget, returning its node id and widget id. */
    private fun findWidget(node: JsonElement): Pair<Long, String>? {
        val obj = node.jsonObject

        val attributes = (obj["attributes"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
            .chunked(2)
            .associate { it[0] to it.getOrElse(1) { "" } }
        val id = attributes["id"].orEmpty()
        val src = attributes["src"].orEmpty()
        val isWidget = id.startsWith(TURNSTILE_ID_PREFIX) ||
            (obj["nodeName"]?.jsonPrimitive?.content == "IFRAME" && src.contains("challenges.cloudflare.com"))

        if (isWidget) {
            val nodeId = obj["nodeId"]?.jsonPrimitive?.content?.toLongOrNull()
            if (nodeId != null) return nodeId to id.ifEmpty { src }
        }

        (obj["shadowRoots"] as? JsonArray)?.forEach { shadow ->
            findWidget(shadow)?.let { return it }
        }
        (obj["children"] as? JsonArray)?.forEach { child ->
            findWidget(child)?.let { return it }
        }
        return null
    }

    fun click(page: Page, point: ChallengePoint) {
        val common = mapOf(
            "x" to JsonPrimitive(point.x),
            "y" to JsonPrimitive(point.y),
            "button" to JsonPrimitive("left"),
            "clickCount" to JsonPrimitive(1),
        )
        page.send(
            "Input.dispatchMouseEvent",
            JsonObject(common + ("type" to JsonPrimitive("mouseMoved")) + ("buttons" to JsonPrimitive(0))),
        )
        page.send(
            "Input.dispatchMouseEvent",
            JsonObject(common + ("type" to JsonPrimitive("mousePressed")) + ("buttons" to JsonPrimitive(1))),
        )
        page.send(
            "Input.dispatchMouseEvent",
            JsonObject(common + ("type" to JsonPrimitive("mouseReleased")) + ("buttons" to JsonPrimitive(0))),
        )
    }

    private const val CHECKBOX_OFFSET_X = 30.0
}

/**
 * Clears a Cloudflare challenge for a URL and hands back the cookies the browser earned.
 *
 * Blocking, because it is called from an OkHttp interceptor. It drives a bare DevTools page rather
 * than `android.webkit.WebView` on purpose: the interceptor runs on a network thread, and a WebView
 * would hop through the single main thread, which a source that is itself blocked on this request
 * may already be occupying.
 *
 * Whether the earned clearance is accepted is up to the zone: `cf_clearance` is bound to the client
 * that earned it, so some zones accept a replay from another client and some refuse it. The caller
 * replays once and reports a block if the challenge comes back; [solve] itself makes a single
 * attempt per host per [COOLDOWN_MS], so an extension the browser cannot clear fails fast instead of
 * spending seconds on every request.
 */
object CloudflareSolver {

    private val lastAttempt = ConcurrentHashMap<String, Long>()

    /**
     * Returns the `Cookie` header the browser holds for [url] after clearing its challenge, or
     * null when it was not cleared.
     *
     * [userAgent] is applied to the browser because Cloudflare binds `cf_clearance` to the user
     * agent; the follow-up request has to look like the browser that earned the cookie.
     */
    fun solve(url: String, userAgent: String, timeout: Duration = 20.seconds): String? {
        if (!autoSolveEnabled) return null

        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        val previous = lastAttempt.put(host, now)
        // One attempt per host per minute: a zone that refuses this browser stays refused, so
        // retrying would only add latency to every request the source makes.
        if (previous != null && now - previous < COOLDOWN_MS) return null

        return runCatching { attempt(url, userAgent, timeout) }.getOrNull()
    }

    private fun attempt(url: String, userAgent: String, timeout: Duration): String? {
        val page = Chrome.newPage()
        try {
            page.send("Page.enable")
            page.send("Network.enable")
            page.send("DOM.enable")
            ChallengeSolver.installWatcher(page)
            page.send(
                "Network.setUserAgentOverride",
                JsonObject(mapOf("userAgent" to JsonPrimitive(userAgent))),
            )
            page.send("Page.navigate", JsonObject(mapOf("url" to JsonPrimitive(url))))

            val clicked = mutableSetOf<String>()
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds

            while (System.nanoTime() < deadline) {
                Thread.sleep(POLL_MS)

                cookiesFor(page, url).takeIf { it.contains("cf_clearance") }?.let { return it }

                val probe = ChallengeSolver.probe(page)
                val key = probe.widgetKey
                val point = probe.clickAt
                // `clickAt` is only reported once the widget accepts input, so this never clicks
                // into a half-built widget, and the key makes each widget clickable once.
                if (key != null && point != null && clicked.add(key)) {
                    ChallengeSolver.click(page, point)
                }
            }
            return null
        } finally {
            page.close()
        }
    }

    /** The `Cookie` header form of every cookie the browser would send to [url]. */
    private fun cookiesFor(page: Page, url: String): String {
        val result = runCatching {
            page.call(
                "Network.getCookies",
                JsonObject(mapOf("urls" to JsonArray(listOf(JsonPrimitive(url))))),
            )
        }.getOrNull() ?: return ""

        val cookies = result["cookies"] as? JsonArray ?: return ""
        return cookies.joinToString("; ") { element ->
            val cookie = element.jsonObject
            val name = cookie["name"]?.jsonPrimitive?.content.orEmpty()
            val value = cookie["value"]?.jsonPrimitive?.content.orEmpty()
            "$name=$value"
        }
    }

    private const val POLL_MS = 250L

    /** One solve attempt per host per minute, so a refusal is not paid for on every request. */
    private const val COOLDOWN_MS = 60_000L
}
