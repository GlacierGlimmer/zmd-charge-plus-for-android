package com.glacierglimmer.endfieldchargeplus.ui.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot DeepSeek credential/endpoint check used by the data sources page.
 *
 * It performs exactly the request the collector performs (`GET /user/balance` with a Bearer token)
 * and reports the transport outcome. The API key is never logged: only the HTTP status and the
 * transport error class are surfaced.
 */
object DeepSeekTest {

    data class Outcome(
        val reachable: Boolean,
        val httpStatus: Int? = null,
        /** Machine readable transport/protocol note, never containing the key. */
        val note: String = "",
    )

    suspend fun test(apiKey: String, baseUrl: String, timeoutMs: Int = 10_000): Outcome =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Outcome(false, note = "missing_key")
            val endpoint = baseUrl.trim().trimEnd('/') + "/user/balance"
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                return@withContext Outcome(false, note = "invalid_base_url")
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                }
                val status = connection.responseCode
                Outcome(reachable = status in 200..299, httpStatus = status, note = "http_$status")
            } catch (error: Exception) {
                Outcome(false, httpStatus = null, note = error.javaClass.simpleName)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
}
