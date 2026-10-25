package eu.kanade.tachiyomi.network.interceptor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.network.InMemoryCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks the interceptor's three outcomes: ordinary responses pass through, a cleared challenge is
 * replayed once, and a challenge that stays up is reported without a second solve.
 *
 * The solver is injected, so these cases run without a browser and pin down the decision the
 * interceptor makes rather than how the browser clears a challenge.
 */
class CloudflareInterceptorTest {

    private lateinit var server: HttpServer

    @Volatile
    private var challengeCode = 403

    @Volatile
    private var headers = emptyMap<String, String>()

    /** The body served while a challenge is up, so tests can imitate a real interstitial. */
    @Volatile
    private var challengeBody = "challenge"

    /** True while the server answers with a challenge, as a zone does until it is cleared. */
    @Volatile
    private var challenging = false

    private val requests = AtomicInteger()

    private val solveCalls = AtomicInteger()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            requests.incrementAndGet()
            val challenged = challenging
            if (challenged) {
                headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            }
            val body = (if (challenged) challengeBody else "ok").toByteArray()
            exchange.sendResponseHeaders(if (challenged) challengeCode else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client(solve: (String) -> String? = { _ ->
        solveCalls.incrementAndGet()
        null
    }): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(InMemoryCookieJar(), solve))
        .build()

    private fun request(): Request =
        Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()

    @Test
    fun `ordinary responses pass through`() {
        client().newCall(request()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertEquals("the solver ran for an ordinary response", 0, solveCalls.get())
    }

    @Test
    fun `a cleared challenge is replayed once`() {
        challenging = true
        headers = mapOf("cf-mitigated" to "challenge")

        // The first request is challenged, then the zone accepts the browser's clearance.
        val client = OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(
                CloudflareInterceptor(InMemoryCookieJar()) { _ ->
                    solveCalls.incrementAndGet()
                    challenging = false
                    "cf_clearance=token"
                },
            )
            .build()

        client.newCall(request()).execute().use { response ->
            assertEquals("the replay was not accepted", 200, response.code)
            assertEquals("ok", response.body.string())
        }
        assertEquals("solved more than once", 1, solveCalls.get())
        assertEquals("expected the challenge and exactly one replay", 2, requests.get())
    }

    @Test
    fun `a challenge that survives the replay fails fast`() {
        challenging = true
        headers = mapOf("cf-mitigated" to "challenge")

        // The browser earns a clearance, but this zone refuses to honour it for the replay.
        val thrown = runCatching {
            client { _ ->
                solveCalls.incrementAndGet()
                "cf_clearance=token"
            }.newCall(request()).execute()
        }.exceptionOrNull()

        assertNotNull("challenge response was not reported", thrown)
        assertTrue("wrong exception: $thrown", thrown is AntiBotChallengeException)
        assertEquals("Cloudflare", (thrown as AntiBotChallengeException).provider)
        assertEquals(403, thrown.statusCode)

        assertEquals("solved more than once", 1, solveCalls.get())
        assertEquals("expected the challenge and exactly one replay", 2, requests.get())
    }

    @Test
    fun `an unsolvable challenge is not retried`() {
        challenging = true
        headers = mapOf("cf-mitigated" to "challenge")

        val thrown = runCatching { client().newCall(request()).execute() }.exceptionOrNull()

        assertNotNull("challenge response was not reported", thrown)
        assertTrue("wrong exception: $thrown", thrown is AntiBotChallengeException)
        assertEquals("solved more than once", 1, solveCalls.get())
        assertEquals("the challenged request was retried", 1, requests.get())
    }

    @Test
    fun `aws waf challenges are reported without solving`() {
        challenging = true
        challengeCode = 202
        headers = mapOf("x-amzn-waf-action" to "challenge")

        val thrown = runCatching { client().newCall(request()).execute() }.exceptionOrNull()

        assertNotNull("challenge response was not reported", thrown)
        assertTrue("wrong exception: $thrown", thrown is AntiBotChallengeException)
        assertEquals("AWS WAF", (thrown as AntiBotChallengeException).provider)
        assertEquals("the solver ran for a challenge no click can clear", 0, solveCalls.get())
        assertEquals("the challenged request was retried", 1, requests.get())
    }

    @Test
    fun `a site's own 403 behind Cloudflare is left to the extension`() {
        challenging = true
        // Every response from a Cloudflare-fronted site carries `cf-ray`, including the site's own.
        headers = mapOf("cf-ray" to "a4ec14878c293f98-SIN", "server" to "cloudflare")
        challengeBody = """<div class="mhcl-lock-box">This chapter is locked</div>"""

        client().newCall(request()).execute().use { response ->
            assertEquals("the site's answer was replaced by a challenge", 403, response.code)
            assertTrue("the site's body was not delivered", response.body.string().contains("mhcl-lock-box"))
        }
        assertEquals("the solver ran for a 403 that is not a challenge", 0, solveCalls.get())
        assertEquals("the site's answer was retried", 1, requests.get())
    }

    @Test
    fun `a challenge page without the mitigated header is still solved`() {
        challenging = true
        headers = mapOf("cf-ray" to "a4ec14878c293f98-SIN", "server" to "cloudflare")
        challengeBody = """<html><head><title>Just a moment...</title></head>
            <body><script>window._cf_chl_opt = {}</script></body></html>"""

        val thrown = runCatching { client().newCall(request()).execute() }.exceptionOrNull()

        assertNotNull("challenge response was not reported", thrown)
        assertTrue("wrong exception: $thrown", thrown is AntiBotChallengeException)
        assertEquals("the challenge page was not solved", 1, solveCalls.get())
    }

    @Test
    fun `a firewall block is reported without solving`() {
        challenging = true
        headers = mapOf("cf-ray" to "a4ec14878c293f98-SIN", "server" to "cloudflare")
        challengeBody = """<html><head><title>Attention Required! | Cloudflare</title></head>
            <body><div id="cf-error-details">Sorry, you have been blocked</div></body></html>"""

        val thrown = runCatching { client().newCall(request()).execute() }.exceptionOrNull()

        assertNotNull("block response was not reported", thrown)
        assertTrue("wrong exception: $thrown", thrown is AntiBotChallengeException)
        assertEquals("the solver ran for a block no browser can clear", 0, solveCalls.get())
        assertEquals("the blocked request was retried", 1, requests.get())
    }

    @Test
    fun `a 403 from a non-Cloudflare site passes through without body peek`() {
        challenging = true
        // No server: cloudflare header — this is a site that does not use Cloudflare at all.
        headers = mapOf("server" to "nginx")
        challengeBody = "Forbidden"

        client().newCall(request()).execute().use { response ->
            assertEquals("the site's answer was replaced", 403, response.code)
            assertEquals("Forbidden", response.body.string())
        }
        assertEquals("the solver ran for a non-Cloudflare 403", 0, solveCalls.get())
        assertEquals("the request was retried", 1, requests.get())
    }
}
