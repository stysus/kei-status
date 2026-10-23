@file:Suppress("Unused", "UnusedReceiverParameter", "RedundantSuspendModifier")

package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/**
 * Runs the call off the main thread.
 *
 * Deliberately blocking rather than `enqueue`-based: OkHttp only delivers [java.io.IOException]
 * from an interceptor through `onFailure`, and rethrows anything else into its dispatcher thread.
 * An extension interceptor that hits a missing class (`NoClassDefFoundError`) or any other
 * `Throwable` would therefore leave the coroutine suspended forever instead of reporting the real
 * cause. With `execute()` every throwable reaches the caller.
 *
 * Cancelling the coroutine cancels the call, which unblocks the socket and lets `withTimeout` work.
 */
suspend fun Call.await(): Response = withContext(Dispatchers.IO) {
    val onCancel = coroutineContext.job.invokeOnCompletion { cause ->
        if (cause is CancellationException) runCatching { cancel() }
    }

    try {
        execute()
    } finally {
        onCancel.dispose()
    }
}

/** Similar to [await] but throws [HttpException] if [Response.isSuccessful] is false. */
suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        // `Retry-After` is how the server says when to come back. Only the delta-seconds form is
        // read: the HTTP-date form needs a clock and a parser, and every site that throttles a
        // sweep so far sends the number.
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        response.close()
        throw HttpException(response.code, retryAfter)
    }
    return response
}
