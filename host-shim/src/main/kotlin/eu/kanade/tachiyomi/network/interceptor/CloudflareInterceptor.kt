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
 * that browser itself, clicks the widget and copies the cookies it earned into the jar, so the
 * replay looks like the browser that cleared the challenge.
 *
 * Not every zone accepts that replay: `cf_clearance` is bound to the client that earned it, and a
 * zone that enforces that binding refuses the replayed request even with an identical user agent.
 * When that happens the challenge is reported immediately instead of being solved again - the
 * solver already limits itself to one attempt per host per minute, so an extension this browser
 * cannot clear fails fast rather than paying for a solve on every request.
 *
 * Anything that cannot be replayed (a request with a body), and challenges no click can clear
 * (AWS WAF), are reported as [AntiBotChallengeException] without being solved.
 *
 * Named `CloudflareInterceptor` because `KeiSource.client` asserts on that exact simple name and
 * moves the interceptor to the end of the chain, which is what lets it see the final user agent
 * that the clearance cookie is bound to.
 */
class CloudflareInterceptor(
    private val cookieJar: CookieJar,
    private val solve: (url: String, userAgent: String) -> String? = { url, userAgent ->
        CloudflareSolver.solve(url, userAgent)
    },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val provider = challengeProvider(response) ?: return response

        val code = response.code
        val host = request.url.host
        response.close()

        if (provider == CLOUDFLARE && request.body == null) {
            val cookies = solve(request.url.toString(), request.header("User-Agent").orEmpty())
            if (cookies != null) {
                save(request.url, cookies)
                val retried = chain.proceed(request)
                if (challengeProvider(retried) == null) return retried
                retried.close()
            }
        }

        throw AntiBotChallengeException(code, host, provider)
    }

    /** Writes the browser's cookies into the jar, which is what the replay reads from. */
    private fun save(url: HttpUrl, cookieHeader: String) {
        val cookies = cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .mapNotNull { Cookie.parse(url, "$it; Path=/") }
        cookieJar.saveFromResponse(url, cookies)
    }

    private fun challengeProvider(response: Response): String? = when {
        response.header("cf-mitigated").equals("challenge", ignoreCase = true) -> CLOUDFLARE

        // AWS WAF answers with 202 and an empty body, tagged by this header.
        response.header("x-amzn-waf-action").equals("challenge", ignoreCase = true) -> AWS_WAF

        // Cloudflare blocks without `cf-mitigated` on older zones.
        response.header("cf-ray") != null && response.code in setOf(403, 503) -> CLOUDFLARE

        else -> null
    }

    private companion object {
        const val CLOUDFLARE = "Cloudflare"
        const val AWS_WAF = "AWS WAF"
    }
}
