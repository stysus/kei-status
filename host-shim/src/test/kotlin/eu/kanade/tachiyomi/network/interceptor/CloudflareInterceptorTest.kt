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
            val body = (if (challenged) "challenge" else "ok").toByteArray()
            exchange.sendResponseHeaders(if (challenged) challengeCode else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client(solve: (String, String) -> String? = { _, _ ->
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
                CloudflareInterceptor(InMemoryCookieJar()) { _, _ ->
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
            client { _, _ ->
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
}
