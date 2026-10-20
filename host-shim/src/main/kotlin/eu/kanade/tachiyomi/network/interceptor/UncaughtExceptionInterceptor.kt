package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rethrows anything thrown downstream untouched. The app uses this to guarantee that a crash in
 * an interceptor surfaces as a source error rather than being swallowed by OkHttp's dispatcher.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
