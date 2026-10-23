package eu.kanade.tachiyomi.network

/**
 * Thrown when a response is not successful. Mirrors the host app's exception so extension code
 * that catches it by type keeps working.
 *
 * `@JvmOverloads` keeps the upstream `HttpException(int)` constructor. Without it the optional
 * `retryAfterSeconds` collapses the primary constructor into `(int, Long)` plus a default-marker
 * stub, and every `throw HttpException(response.code)` in extension bytecode - compiled against
 * `HttpException(int)` - fails with NoSuchMethodError instead of throwing.
 */
class HttpException @JvmOverloads constructor(
    val code: Int,
    val retryAfterSeconds: Long? = null,
) : IllegalStateException("HTTP error $code")
