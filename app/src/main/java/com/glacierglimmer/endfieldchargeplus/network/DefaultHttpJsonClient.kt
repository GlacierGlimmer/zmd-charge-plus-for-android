package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * `HttpJsonClient` implementation used by the custom HTTP/JSON data sources.
 *
 * Hardening compared with the desktop editions (where the audit explicitly flags the gap):
 *  * HTTPS only, except loopback (`127.0.0.1`, `localhost`, `::1`) — see [HttpUrlPolicy];
 *  * explicit connect and read timeouts on every request;
 *  * a 512 KB response cap ([MAX_RESPONSE_BYTES]); a larger body fails instead of being truncated;
 *  * explicit gzip handling and UTF-8 decoding ([UrlConnectionTransport]);
 *  * no automatic retries: a failure is returned once and the caller owns the backoff — this is
 *    what prevents a broken source from becoming a request storm.
 *
 * Failures are returned as `Result.failure` and the last one is also kept for the diagnostics page
 * through [lastError]. Log lines and error messages only ever contain the host name, never the full
 * request URI (it may carry a token) and never a header value.
 */
class DefaultHttpJsonClient(
    private val transport: HttpTransport = UrlConnectionTransport,
) : HttpJsonClient {

    @Volatile
    private var lastErrorMessage: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    override suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): Result<JsonElement> {
        HttpUrlPolicy.rejectionReason(url)?.let { reason ->
            return failure(url, reason)
        }
        val effectiveTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return try {
            val response = transport.get(url, headers, effectiveTimeout, MAX_RESPONSE_BYTES)
            if (response.statusCode !in 200..299) {
                failure(url, "http_${response.statusCode}")
            } else {
                lastErrorMessage = null
                Result.success(json.parseToJsonElement(response.body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(url, describe(e))
        }
    }

    override fun lastError(): String? = lastErrorMessage

    private fun failure(url: String, reason: String): Result<JsonElement> {
        val host = HttpUrlPolicy.hostOf(url)
        val message = if (host.isEmpty()) reason else "$reason @ $host"
        lastErrorMessage = message
        AppLog.w(TAG, "HTTP/JSON source request failed: $message")
        return Result.failure(IOException(message))
    }

    private fun describe(e: Exception): String = when (e) {
        is IOException -> e.message ?: "io_error"
        else -> e.javaClass.simpleName
    }

    companion object {
        const val TAG = "HttpJsonClient"
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MIN_TIMEOUT_MS = 250
        const val MAX_TIMEOUT_MS = 60_000
    }
}
