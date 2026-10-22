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
 * (AWS WAF), are reported as [AntiBotChallengeException] without being solved.
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

        val provider = challengeProvider(response) ?: return response

        val code = response.code
        val host = request.url.host
        response.close()

        if (provider == CLOUDFLARE && request.body == null) {
            val cookies = solve(request.url.toString())
            if (cookies != null) {
                save(request.url, cookies)
                // The clearance is only bound to a user agent once the browser has earned it, so
                // this is read after the solve rather than before.
                val replay = CloudflareSolver.userAgentFor(host)
                    ?.let { request.newBuilder().header("User-Agent", it).build() }
                    ?: request
                val retried = chain.proceed(replay)
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
