package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebSettings
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the default client the way the app does.
 *
 * `KeiSource` asserts on the presence of three interceptors by simple name before it builds the
 * source's own client, so they cannot be omitted or renamed.
 */
class NetworkHelper(private val context: Context) {

    val cookieJar = InMemoryCookieJar()

    val userAgent: String = System.getenv("KEI_STATUS_USER_AGENT")
        ?: WebSettings.DEFAULT_USER_AGENT

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .cache(
                okhttp3.Cache(
                    directory = File(context.getCacheDir(), "network_cache"),
                    maxSize = 5L * 1024 * 1024,
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            .addInterceptor(CloudflareInterceptor(cookieJar))
            .build()
    }

    fun defaultUserAgentProvider(): String = userAgent
}
