package com.glacierglimmer.endfieldchargeplus.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/** A raw HTTP response: the status code plus the decoded (and gunzipped) body. */
data class HttpResponse(val statusCode: Int, val body: String)

/**
 * The single blocking HTTP GET primitive of the application.
 *
 * It is an interface only so that the two API clients (`DefaultHttpJsonClient` and
 * `DefaultDeepSeekClient`) stay unit-testable without a network; production code always uses
 * [UrlConnectionTransport], which contains every `HttpURLConnection` detail in one place.
 *
 * Implementations must never log request headers, because the DeepSeek API key is carried in one.
 */
interface HttpTransport {
    /**
     * Performs one blocking GET.
     *
     * @param headers request headers; never logged by implementations.
     * @param timeoutMs connect and read timeout.
     * @param maxBytes hard cap on the decoded body; exceeding it fails instead of truncating.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        maxBytes: Int,
    ): HttpResponse
}

/**
 * Cleartext policy shared by both HTTP clients.
 *
 * ECP's Android port deliberately hardens what the desktop editions never restricted: user-defined
 * sources and the DeepSeek base URL may only use HTTPS. Plain `http://` is accepted solely for
 * loopback hosts, which is what a local mock/debug server needs and what cannot leak over the air.
 */
object HttpUrlPolicy {

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

    /**
     * `null` when [url] may be requested, otherwise a short machine readable rejection code such as
     * `cleartext_http_rejected`, `unsupported_scheme` or `invalid_url`.
     */
    fun rejectionReason(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return "invalid_url"
        }
        val scheme = uri.scheme?.lowercase() ?: return "invalid_url"
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return "invalid_url"
        return when {
            scheme == "https" -> null
            scheme == "http" && host in LOOPBACK_HOSTS -> null
            scheme == "http" -> "cleartext_http_rejected"
            else -> "unsupported_scheme"
        }
    }

    /** Host of [url], or an empty string when it cannot be parsed; safe to log. */
    fun hostOf(url: String): String =
        try {
            URI(url.trim()).host ?: ""
        } catch (_: Exception) {
            ""
        }
}

/**
 * Production [HttpTransport] built on `HttpURLConnection`.
 *
 * Behaviour, all of it deliberate:
 *  * connect and read timeouts both come from the caller;
 *  * redirects are followed manually (at most [MAX_REDIRECTS]) so that every hop is re-checked
 *    against [HttpUrlPolicy] — a redirect to cleartext must not slip past the policy;
 *  * gzip is requested and decoded explicitly instead of relying on platform behaviour;
 *  * the body is capped at `maxBytes` (512 KB by default) and an oversized body fails instead of
 *    being truncated into invalid JSON;
 *  * there is no retry loop at all: a failed request is reported to the caller, which owns backoff.
 */
object UrlConnectionTransport : HttpTransport {

    private const val MAX_REDIRECTS = 3
    private const val READ_BUFFER_BYTES = 8 * 1024

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        maxBytes: Int,
    ): HttpResponse = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        var result: HttpResponse? = null

        while (result == null) {
            val connection = try {
                URL(currentUrl).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw IOException("invalid_url")
            }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "gzip")
                for ((name, value) in headers) {
                    runCatching { connection.setRequestProperty(name, value) }
                }

                val code = connection.responseCode
                val location = if (code in 300..399) connection.getHeaderField("Location") else null
                if (location != null && redirects < MAX_REDIRECTS) {
                    val next = try {
                        URL(URL(currentUrl), location).toString()
                    } catch (_: Exception) {
                        throw IOException("bad_redirect")
                    }
                    HttpUrlPolicy.rejectionReason(next)?.let { throw IOException(it) }
                    currentUrl = next
                    redirects++
                } else {
                    val stream = if (code in 200..299) {
                        runCatching { connection.inputStream }.getOrNull()
                    } else {
                        connection.errorStream
                    }
                    result = HttpResponse(code, readBody(stream, connection.contentEncoding, maxBytes))
                }
            } finally {
                connection.disconnect()
            }
        }
        result
    }

    private fun readBody(stream: InputStream?, contentEncoding: String?, maxBytes: Int): String {
        if (stream == null) return ""
        val decoded = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        decoded.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("response_too_large")
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
