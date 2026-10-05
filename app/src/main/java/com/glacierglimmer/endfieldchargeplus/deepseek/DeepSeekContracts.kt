package com.glacierglimmer.endfieldchargeplus.deepseek

/** Balance/status payload of the DeepSeek API, as used by the "余额 / 时段" scheme. */
data class DeepSeekBalance(
    val available: Boolean,
    val currency: String = "CNY",
    val totalBalance: Double? = null,
    val grantedBalance: Double? = null,
    val toppedUpBalance: Double? = null,
    val error: String? = null,
) {
    companion object {
        fun failure(message: String) = DeepSeekBalance(available = false, error = message)
    }
}

/**
 * DeepSeek balance client.
 *
 * The key is passed in per call (it lives in encrypted storage) and must never be logged: see
 * `AppLog.registerSecret`.
 */
interface DeepSeekClient {
    suspend fun fetchBalance(apiKey: String, baseUrl: String, timeoutMs: Int = 10_000): DeepSeekBalance
}
