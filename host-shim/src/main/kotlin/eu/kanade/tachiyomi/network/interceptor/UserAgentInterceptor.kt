package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/** Fills in a default User-Agent when a request does not already set one. */
class UserAgentInterceptor(
    private val userAgentProvider: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("User-Agent", userAgentProvider())
                .build(),
        )
    }
}
