package eu.kanade.tachiyomi.network

import okhttp3.Response

/**
 * Thrown when a response is not successful. Mirrors the host app's exception so extension code
 * that catches it by type keeps working.
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
