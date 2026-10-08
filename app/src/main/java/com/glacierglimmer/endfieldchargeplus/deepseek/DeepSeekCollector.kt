package com.glacierglimmer.endfieldchargeplus.deepseek

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.data.SecretStore
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.metrics.MetricCollector
import com.glacierglimmer.endfieldchargeplus.metrics.MetricEnvironment
import com.glacierglimmer.endfieldchargeplus.network.ConnectivityStatus
import com.glacierglimmer.endfieldchargeplus.network.HttpSourceMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.io.File

/**
 * IDLE-tier collector for the DeepSeek "余额 / 时段" scheme.
 *
 * The peak/off-peak half uses a local calendar: it is recomputed on every tick so the
 * countdown stays live. The balance half is polled at most once per minute — the
 * `AppConfig.android.deepSeekRefreshSeconds` cadence clamped to >= 60 s, with exponential backoff on
 * failure — because a HUD rendering at 2 Hz must never be able to drive an API request rate.
 *
 * Data honesty, per the audit's "never fabricate":
 *  * no API key configured → balance and currency are `Unavailable(PERMISSION_REQUIRED)` and
 *    `deepseek.status` is `no_key`; the UI explains that a key is needed, no `¥0.00` is invented;
 *  * a failed request → `Unavailable(NETWORK_ERROR)`, never the previous balance;
 *  * the key is read from the injected [SecretStore] and only ever leaves this class inside the
 *    `Authorization` header ([DefaultDeepSeekClient] registers it for log redaction).
 */
class DeepSeekCollector(
    private val context: Context,
    private val environment: MetricEnvironment,
    private val secretStore: SecretStore,
    private val client: DeepSeekClient = DefaultDeepSeekClient(),
) : MetricCollector {

    override val id: String = "deepseek"

    override val tier: SamplingTier = SamplingTier.IDLE

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val calendarUpdater by lazy { ChinaCalendarUpdater(File(context.filesDir, "holiday-calendar")) }

    @Volatile
    private var balance: DeepSeekBalance? = null

    @Volatile
    private var status: String = STATUS_IDLE

    @Volatile
    private var updatedAtMs: Long = 0L

    @Volatile
    private var nextAttemptAtMs: Long = 0L

    @Volatile
    private var failureCount: Int = 0

    @Volatile
    private var inFlight: Boolean = false

    @Volatile
    private var keyFingerprint: Int = 0

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val config = environment.config()
        val english = useEnglish(config)
        calendarUpdater.requestRefresh(scope, System.currentTimeMillis())

        val period = DeepSeekPeriodPolicy.evaluate(ZonedDateTime.now(PeakWindow.BEIJING))
        publishPeriod(into, period, english)

        val key = secretStore.get(SecretStore.KEY_DEEPSEEK_API_KEY)?.trim().orEmpty()
        if (key.isEmpty()) {
            status = STATUS_NO_KEY
            balance = null
            updatedAtMs = 0L
            inFlight = false
            publishBalance(into)
            return
        }

        AppLog.registerSecret(key)
        if (key.hashCode() != keyFingerprint) {
            keyFingerprint = key.hashCode()
            balance = null
            updatedAtMs = 0L
            failureCount = 0
            status = STATUS_IDLE
            nextAttemptAtMs = 0L
        }

        val refreshMs = refreshIntervalMs(config)
        val now = System.currentTimeMillis()
        if (!inFlight && now >= nextAttemptAtMs && ConnectivityStatus.isOnline(context)) {
            inFlight = true
            // Provisional guard: a slow request must not be re-launched on the next tick.
            nextAttemptAtMs = now + MIN_INTERVAL_MS
            scope.launch { fetchBalance(key, config.android.deepSeekBaseUrl, refreshMs) }
        }
        publishBalance(into)
    }

    private suspend fun fetchBalance(key: String, baseUrl: String, refreshMs: Long) {
        val result = try {
            client.fetchBalance(key, baseUrl)
        } catch (e: CancellationException) {
            inFlight = false
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Balance fetch aborted (${e.javaClass.simpleName})")
            DeepSeekBalance.failure(DefaultDeepSeekClient.ERROR_REQUEST_FAILED)
        }

        val now = System.currentTimeMillis()
        if (result.error == null) {
            balance = result
            status = if (result.available) STATUS_OK else STATUS_UNAVAILABLE
            updatedAtMs = now
            failureCount = 0
            nextAttemptAtMs = now + refreshMs
        } else {
            balance = null
            status = STATUS_ERROR
            failureCount += 1
            val backoff = HttpSourceMapping.backoffDelayMs(refreshMs, failureCount)
                .coerceAtLeast(MIN_INTERVAL_MS)
            nextAttemptAtMs = now + backoff
        }
        inFlight = false
        // Status only: the API key must never reach the log.
        AppLog.i(TAG, "DeepSeek balance refresh: $status")
    }

    private fun publishBalance(into: MutableMap<String, MetricValue>) {
        into[Variables.DEEPSEEK_STATUS] = MetricValue.Text(status)

        val current = balance
        if (current != null && current.error == null) {
            into[Variables.DEEPSEEK_BALANCE] =
                current.totalBalance?.let { MetricValue.Number(it) } ?: MetricValue.NoData
            into[Variables.DEEPSEEK_CURRENCY] = MetricValue.Text(current.currency)
        } else {
            val reason = if (status == STATUS_NO_KEY) {
                UnavailableReason.PERMISSION_REQUIRED
            } else if (status == STATUS_ERROR) {
                UnavailableReason.NETWORK_ERROR
            } else {
                UnavailableReason.NO_DATA
            }
            into[Variables.DEEPSEEK_BALANCE] = MetricValue.Unavailable(reason, status)
            into[Variables.DEEPSEEK_CURRENCY] = MetricValue.Unavailable(reason, status)
        }

        into[Variables.DEEPSEEK_UPDATED_AT] = when {
            updatedAtMs > 0L -> MetricValue.Text(formatTimestamp(updatedAtMs))
            status == STATUS_NO_KEY -> MetricValue.PermissionRequired
            else -> MetricValue.NoData
        }
    }

    private fun publishPeriod(
        into: MutableMap<String, MetricValue>,
        period: DeepSeekPeriodPolicy.State,
        english: Boolean,
    ) {
        into[Variables.DEEPSEEK_IS_PEAK] = period.isPeak?.let { MetricValue.Number(if (it) 1.0 else 0.0) }
            ?: MetricValue.Unavailable(UnavailableReason.NO_DATA, "calendar_update_required")
        into[Variables.DEEPSEEK_PERIOD_PROGRESS] = MetricValue.number(period.progressPercent)
        into[Variables.DEEPSEEK_PERIOD_NAME] = MetricValue.Text(period.nameEn)
        into[Variables.DEEPSEEK_PERIOD_NAME_ZH] =
            MetricValue.Text(if (english) period.nameEn else period.nameZh)
        into[Variables.DEEPSEEK_PERIOD_REMAINING_TEXT] = MetricValue.Text(period.remainingText(english))
        into[Variables.DEEPSEEK_PERIOD_PROGRESS_TEXT] = MetricValue.Text(period.progressText(english))
    }

    private fun refreshIntervalMs(config: AppConfig): Long =
        config.android.deepSeekRefreshSeconds.coerceAtLeast(MIN_REFRESH_SECONDS) * 1_000L

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun useEnglish(config: AppConfig): Boolean = when (config.language) {
        AppLanguage.ENGLISH -> true
        AppLanguage.SIMPLIFIED_CHINESE -> false
        AppLanguage.AUTO -> Locale.getDefault().language.equals("en", ignoreCase = true)
    }

    companion object {
        const val TAG = "DeepSeekCollector"

        /** Hard floor from the brief: DeepSeek is never requested more than once per minute. */
        const val MIN_INTERVAL_MS = 60_000L

        /** Minimum configured cadence, in seconds. */
        const val MIN_REFRESH_SECONDS = 60

        /** `deepseek.status` values. */
        const val STATUS_IDLE = "idle"
        const val STATUS_OK = "ok"
        const val STATUS_NO_KEY = "no_key"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val STATUS_ERROR = "error"
    }
}
