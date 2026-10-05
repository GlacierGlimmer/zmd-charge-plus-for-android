package com.glacierglimmer.endfieldchargeplus.deepseek

import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.network.HttpTransport
import com.glacierglimmer.endfieldchargeplus.network.HttpUrlPolicy
import com.glacierglimmer.endfieldchargeplus.network.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * DeepSeek balance client.
 *
 * `GET {baseUrl}/user/balance` with `Authorization: Bearer <key>`, an explicit timeout and the
 * ECP response contract: `is_available` plus the `balance_infos[]` entry, preferring the CNY entry
 * and falling back to the first one when the account reports another currency.
 *
 * Secret handling is non-negotiable:
 *  * the key is registered with [AppLog.registerSecret] before the first request, so any accidental
 *    occurrence in a later log line is masked;
 *  * this class never logs the key, the `Authorization` header or the full request URI — failures
 *    are reported as short codes ([DeepSeekBalance.error]) and the log only records the exception
 *    *class*, never its message (a message could embed the URL or a header echo);
 *  * cleartext HTTP is rejected except on loopback ([HttpUrlPolicy]), so the bearer token cannot
 *    travel in the clear.
 *
 * The [transport] seam exists only for tests; production uses [UrlConnectionTransport].
 */
class DefaultDeepSeekClient(
    private val transport: HttpTransport = UrlConnectionTransport,
) : DeepSeekClient {

    override suspend fun fetchBalance(
        apiKey: String,
        baseUrl: String,
        timeoutMs: Int,
    ): DeepSeekBalance {
        AppLog.registerSecret(apiKey)
        if (apiKey.isBlank()) return DeepSeekBalance.failure(ERROR_NO_KEY)

        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val url = "$base/user/balance"
        HttpUrlPolicy.rejectionReason(url)?.let { return DeepSeekBalance.failure(it) }

        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
        )
        val response = try {
            transport.get(
                url = url,
                headers = headers,
                timeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
                maxBytes = MAX_RESPONSE_BYTES,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Balance request failed (${e.javaClass.simpleName})")
            return DeepSeekBalance.failure(ERROR_REQUEST_FAILED)
        }
        return parseResponse(response.statusCode, response.body)
    }

    companion object {
        const val TAG = "DeepSeekClient"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 60_000

        const val ERROR_NO_KEY = "no_key"
        const val ERROR_REQUEST_FAILED = "request_failed"
        const val ERROR_INVALID_JSON = "invalid_json"
        const val ERROR_MISSING_BALANCE = "missing_balance"
        const val ERROR_INVALID_BALANCE = "invalid_balance"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Maps one HTTP response to [DeepSeekBalance]. Pure and total: a non-2xx status, invalid
         * JSON or a missing/invalid CNY balance becomes [DeepSeekBalance.failure] with a short,
         * non-secret code — never an exception and never a fabricated amount.
         */
        fun parseResponse(httpStatus: Int, body: String): DeepSeekBalance {
            if (httpStatus !in 200..299) {
                return DeepSeekBalance.failure("http_$httpStatus")
            }
            val root = try {
                json.parseToJsonElement(body)
            } catch (_: Exception) {
                return DeepSeekBalance.failure(ERROR_INVALID_JSON)
            }
            val obj = root as? JsonObject ?: return DeepSeekBalance.failure(ERROR_INVALID_JSON)

            val available = (obj["is_available"] as? JsonPrimitive)?.booleanOrNull == true
            val infos = (obj["balance_infos"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val entry = infos.firstOrNull { currencyOf(it)?.equals("CNY", ignoreCase = true) == true }
                ?: infos.firstOrNull()
                ?: return if (available) {
                    DeepSeekBalance.failure(ERROR_MISSING_BALANCE)
                } else {
                    DeepSeekBalance(available = false)
                }

            val currency = currencyOf(entry)?.takeIf { it.isNotBlank() } ?: "CNY"
            val total = numberOrNull(entry["total_balance"])
            val granted = numberOrNull(entry["granted_balance"])
            val toppedUp = numberOrNull(entry["topped_up_balance"])
            if (available && (total == null || granted == null || toppedUp == null)) {
                return DeepSeekBalance.failure(ERROR_INVALID_BALANCE)
            }

            return DeepSeekBalance(
                available = available,
                currency = currency,
                totalBalance = total,
                grantedBalance = granted,
                toppedUpBalance = toppedUp,
            )
        }

        private fun currencyOf(obj: JsonObject): String? =
            (obj["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun numberOrNull(element: JsonElement?): Double? {
            val primitive = element as? JsonPrimitive ?: return null
            val value = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
            return value?.takeIf { it.isFinite() }
        }
    }
}
