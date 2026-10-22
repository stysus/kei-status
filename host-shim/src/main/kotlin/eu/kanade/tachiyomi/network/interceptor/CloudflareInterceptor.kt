package eu.kanade.tachiyomi.network.interceptor

import kei.status.webkit.CloudflareSolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Raised when a site answers with an anti-bot challenge that the harness could not clear.
 *
 * Extends [java.io.IOException] because OkHttp only delivers `IOException` from an interceptor
 * through `onFailure`; anything else escapes into the dispatcher thread and prints a stack trace.
 */
class AntiBotChallengeException(
    val statusCode: Int,
    val host: String,
    val provider: String,
) : java.io.IOException("$provider challenge from $host (HTTP $statusCode)")

/**
 * Clears a Cloudflare challenge the way the app does, then replays the request once.
 *
 * The app shows the interstitial in a WebView and waits for the user to tap it. The harness runs
 * that browser itself, lets the challenge settle and copies the cookies it earned into the jar, so
 * the replay looks like the browser that cleared the challenge.
 *
 * A zone binds `cf_clearance` to the user agent that earned it. [CloudflareSolver.userAgentFor]
 * reports that user agent, and every request to the host is sent with it, so the cookie keeps
 * working after the first solve instead of being refused as soon as the app's own user agent is
 * used again.
 *
 * Anything that cannot be replayed (a request with a body), and challenges no browser can clear
 * (AWS WAF, a Cloudflare firewall block), are reported as [AntiBotChallengeException] without
 * being solved.
 *
 * A response is only treated as a barrier when Cloudflare identifies itself as the author: a
 * `cf-mitigated` header, or a page Cloudflare served. A site that merely sits behind Cloudflare
 * answers 403 for its own reasons - a chapter that needs an unlock, an age gate - and those
 * responses are returned untouched so the source can say what it meant.
 *
 * Named `CloudflareInterceptor` because `KeiSource.client` asserts on that exact simple name and
 * moves the interceptor to the end of the chain, which is what lets it see the final user agent
 * that the clearance cookie is bound to.
 */
class CloudflareInterceptor(
    private val cookieJar: CookieJar,
    private val solve: (url: String) -> String? = { url -> CloudflareSolver.solve(url) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // A clearance is bound to the user agent that earned it, so once the browser has cleared a
        // host every request to it has to present the same one or the cookie is refused.
        val request = CloudflareSolver.userAgentFor(original.url.host)
            ?.let { original.newBuilder().header("User-Agent", it).build() }
            ?: original
        val response = chain.proceed(request)

        val barrier = barrierOf(response) ?: return response

        val code = response.code
        val host = request.url.host
        response.close()

        if (barrier.solvable && request.body == null) {
            val cookies = solve(request.url.toString())
            if (cookies != null) {
                save(request.url, cookies)
                // The clearance is only bound to a user agent once the browser has earned it, so
                // this is read after the solve rather than before.
                val replay = CloudflareSolver.userAgentFor(host)
                    ?.let { request.newBuilder().header("User-Agent", it).build() }
                    ?: request
                val retried = chain.proceed(replay)
                // A replay that comes back as the site's own answer is the extension's to report,
                // which is why only a barrier counts as still being blocked.
                if (barrierOf(retried) == null) return retried
                retried.close()
            }
        }

        throw AntiBotChallengeException(code, host, barrier.provider)
    }

    /** Writes the browser's cookies into the jar, which is what the replay reads from. */
    private fun save(url: HttpUrl, cookieHeader: String) {
        val cookies = cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .mapNotNull { Cookie.parse(url, "$it; Path=/") }
        cookieJar.saveFromResponse(url, cookies)
    }

    /**
     * What stopped the response, or null when nothing did.
     *
     * `cf-ray` alone is not evidence: it is on every response from a Cloudflare-fronted site,
     * including the site's own 403s, so it cannot say who produced the body. A barrier is only
     * reported when Cloudflare says so itself - through a `cf-mitigated` header, or through a page
     * Cloudflare served. Everything else is the site's answer, and reporting it as a challenge
     * would hide what the site actually said: a chapter lock, an age gate, a login wall.
     */
    private fun barrierOf(response: Response): Barrier? {
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
            return Barrier(CLOUDFLARE, solvable = true)
        }

        // AWS WAF answers with 202 and an empty body, tagged by this header.
        if (response.header("x-amzn-waf-action").equals("challenge", ignoreCase = true)) {
            return Barrier(AWS_WAF, solvable = false)
        }

        if (response.code !in INTERSTITIAL_CODES) return null

        val body = response.prefix().lowercase()
        return when {
            // A firewall block can embed the challenge script, so it is checked first: no browser
            // can turn a block into a puzzle.
            CLOUDFLARE_BLOCK_MARKERS.any { it in body } -> Barrier(CLOUDFLARE, solvable = false)
            CLOUDFLARE_CHALLENGE_MARKERS.any { it in body } -> Barrier(CLOUDFLARE, solvable = true)
            else -> null
        }
    }

    /** The start of the body without consuming it, for recognising a page Cloudflare served. */
    private fun Response.prefix(): String =
        runCatching { peekBody(PEEK_BYTES).string() }.getOrDefault("")

    /** A response Cloudflare produced, and whether a browser could clear it. */
    private data class Barrier(val provider: String, val solvable: Boolean)

    private companion object {
        const val CLOUDFLARE = "Cloudflare"
        const val AWS_WAF = "AWS WAF"

        /** Only these codes carry an interstitial; a 200 is the site's own page. */
        val INTERSTITIAL_CODES = setOf(403, 503)

        /** Enough for any interstitial; Cloudflare's own pages are a few kilobytes. */
        const val PEEK_BYTES = 64L * 1024

        val CLOUDFLARE_CHALLENGE_MARKERS = listOf(
            "_cf_chl_opt",
            "/cdn-cgi/challenge-platform/",
            "cf-browser-verification",
        )

        val CLOUDFLARE_BLOCK_MARKERS = listOf(
            "cf-error-details",
            "you have been blocked",
            "attention required",
        )
    }
}
