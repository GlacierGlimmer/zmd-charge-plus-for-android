package com.glacierglimmer.endfieldchargeplus.network

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.metrics.MetricCollector
import com.glacierglimmer.endfieldchargeplus.metrics.MetricEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * FAST-tier collector that turns the active scheme's probe target into `probe.*` variables.
 *
 * Design rules, all inherited from ECP:
 *  * a probe is issued at most once per `AppConfig.probeIntervalSeconds` (never faster than 1 s) and
 *    only one probe is in flight at a time — a slow network can therefore not queue up requests;
 *  * the collector never blocks the sampler: the probe runs on its own IO scope and this method
 *    publishes the most recent window statistics, exactly like the desktop `PingTargetState`;
 *  * before the first sample arrives every numeric probe variable is `Unavailable(NO_DATA)`;
 *    on a failed probe the latency is *never* faked (`999` is a Windows-only wart) — the numeric
 *    variable stays unavailable and [Variables.PROBE_STATUS_TEXT] carries the localized reason;
 *  * `probe.status` is a stable machine key, [Variables.PROBE_STATUS_TEXT] the human string, and
 *    every `probe.*` value is mirrored to the legacy `ping.*` scheme names.
 */
class ProbeCollector(
    private val context: Context,
    private val environment: MetricEnvironment,
    private val probe: NetworkProbe,
) : MetricCollector {

    override val id: String = "probe"

    override val tier: SamplingTier = SamplingTier.FAST

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val statisticsLock = Any()

    private var statistics = ProbeStatistics(DEFAULT_WINDOW)

    private var currentWindow = DEFAULT_WINDOW

    @Volatile
    private var lastResult: ProbeResult? = null

    @Volatile
    private var inFlight: Boolean = false

    @Volatile
    private var nextAttemptAtMs: Long = 0L

    @Volatile
    private var lastKey: String = ""

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val config = environment.config()
        val settings = config.android
        val profile = environment.activeProfile()
        val target = profile?.pingTarget?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TARGET
        val protocol = ProbeProtocol.fromWire(profile?.probeProtocol)
        val port = (profile?.probePort ?: DefaultNetworkProbe.DEFAULT_PORT)
            .takeIf { it in 1..65535 } ?: DefaultNetworkProbe.DEFAULT_PORT

        if (!settings.probeEnabled) {
            publishDisabled(into, target, protocol, config)
            return
        }

        val window = settings.probeSampleWindow.coerceIn(1, MAX_WINDOW)
        ensureWindow(window)

        val intervalMs = settings.probeIntervalSeconds.coerceAtLeast(1) * 1_000L
        val timeoutMs = settings.probeTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val key = "${protocol.wire}|$target|$port"
        if (key != lastKey) {
            lastKey = key
            lastResult = null
            synchronized(statisticsLock) { statistics.reset() }
        }

        val now = System.currentTimeMillis()
        if (!inFlight && now >= nextAttemptAtMs && ConnectivityStatus.isOnline(context)) {
            inFlight = true
            nextAttemptAtMs = now + intervalMs
            scope.launch { runProbe(target, protocol, port, timeoutMs) }
        }
        publish(into, target, protocol, statusKey(), useEnglish(config))
    }

    private suspend fun runProbe(
        target: String,
        protocol: ProbeProtocol,
        port: Int,
        timeoutMs: Int,
    ) {
        val startedAt = System.currentTimeMillis()
        val result = try {
            probe.probe(target, protocol, port, timeoutMs)
        } catch (e: CancellationException) {
            inFlight = false
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Probe failed: ${e.javaClass.simpleName}")
            ProbeResult(
                timestampMs = startedAt,
                target = target,
                protocol = protocol,
                success = false,
                latencyMs = null,
                error = DefaultNetworkProbe.ERROR_UNREACHABLE,
            )
        }
        synchronized(statisticsLock) { statistics.record(result) }
        lastResult = result
        inFlight = false
    }

    private fun statusKey(): String = when {
        lastResult != null -> statusKeyOf(lastResult!!)
        inFlight -> STATUS_PROBING
        else -> STATUS_IDLE
    }

    private fun statusKeyOf(result: ProbeResult): String = when {
        result.success -> STATUS_OK
        result.error == DefaultNetworkProbe.ERROR_DNS -> STATUS_DNS_ERROR
        result.timeout -> STATUS_TIMEOUT
        result.error == DefaultNetworkProbe.ERROR_UNSUPPORTED -> STATUS_UNSUPPORTED
        else -> STATUS_UNREACHABLE
    }

    private fun publish(
        into: MutableMap<String, MetricValue>,
        target: String,
        protocol: ProbeProtocol,
        status: String,
        english: Boolean,
    ) {
        val stats = synchronized(statisticsLock) {
            ProbeStats(
                sent = statistics.sent(),
                received = statistics.received(),
                lossPercent = statistics.lossPercent(),
                averageLatencyMs = statistics.averageLatencyMs(),
                jitterMs = statistics.jitterMs(),
            )
        }
        val hasSample = stats.sent > 0
        val latency = stats.averageLatencyMs?.let { MetricValue.Number(it) } ?: MetricValue.NoData
        val loss = if (hasSample) MetricValue.Number(stats.lossPercent) else MetricValue.NoData
        val sent = if (hasSample) MetricValue.Number(stats.sent.toDouble()) else MetricValue.NoData
        val received = if (hasSample) MetricValue.Number(stats.received.toDouble()) else MetricValue.NoData
        val jitter = stats.jitterMs?.let { MetricValue.Number(it) } ?: MetricValue.NoData
        val text = MetricValue.Text(statusText(status, english))

        into[Variables.PROBE_TARGET] = MetricValue.Text(target)
        into[Variables.PROBE_PROTOCOL] = MetricValue.Text(protocol.wire)
        into[Variables.PROBE_STATUS] = MetricValue.Text(status)
        into[Variables.PROBE_STATUS_TEXT] = text
        into[Variables.PROBE_LATENCY_MS] = latency
        into[Variables.PROBE_LOSS_PERCENT] = loss
        into[Variables.PROBE_SENT] = sent
        into[Variables.PROBE_RECEIVED] = received
        into[Variables.PROBE_JITTER_MS] = jitter

        // Legacy `ping.*` mirrors used by schemes written before the rename.
        into[PING_LATENCY_MS] = latency
        into[PING_LOSS_PERCENT] = loss
        into[PING_SENT] = sent
        into[PING_RECEIVED] = received
        into[PING_JITTER_MS] = jitter
        into[PING_TARGET] = MetricValue.Text(target)
        into[PING_STATUS] = MetricValue.Text(status)
        into[PING_STATUS_TEXT] = text
    }

    private fun publishDisabled(
        into: MutableMap<String, MetricValue>,
        target: String,
        protocol: ProbeProtocol,
        config: AppConfig,
    ) {
        val disabled = MetricValue.Unavailable(UnavailableReason.DISABLED, "probe_disabled")
        into[Variables.PROBE_TARGET] = MetricValue.Text(target)
        into[Variables.PROBE_PROTOCOL] = MetricValue.Text(protocol.wire)
        into[Variables.PROBE_STATUS] = MetricValue.Text(STATUS_DISABLED)
        into[Variables.PROBE_STATUS_TEXT] = MetricValue.Text(statusText(STATUS_DISABLED, useEnglish(config)))
        into[Variables.PROBE_LATENCY_MS] = disabled
        into[Variables.PROBE_LOSS_PERCENT] = disabled
        into[Variables.PROBE_SENT] = disabled
        into[Variables.PROBE_RECEIVED] = disabled
        into[Variables.PROBE_JITTER_MS] = disabled

        into[PING_LATENCY_MS] = disabled
        into[PING_LOSS_PERCENT] = disabled
        into[PING_SENT] = disabled
        into[PING_RECEIVED] = disabled
        into[PING_JITTER_MS] = disabled
        into[PING_TARGET] = MetricValue.Text(target)
        into[PING_STATUS] = MetricValue.Text(STATUS_DISABLED)
        into[PING_STATUS_TEXT] = MetricValue.Text(statusText(STATUS_DISABLED, useEnglish(config)))
    }

    private fun ensureWindow(window: Int) {
        synchronized(statisticsLock) {
            if (window != currentWindow) {
                currentWindow = window
                statistics = ProbeStatistics(window)
            }
        }
    }

    private fun useEnglish(config: AppConfig): Boolean = when (config.language) {
        AppLanguage.ENGLISH -> true
        AppLanguage.SIMPLIFIED_CHINESE -> false
        AppLanguage.AUTO -> Locale.getDefault().language.equals("en", ignoreCase = true)
    }

    private class ProbeStats(
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
        val averageLatencyMs: Double?,
        val jitterMs: Double?,
    )

    companion object {
        const val TAG = "ProbeCollector"
        const val DEFAULT_TARGET = "1.1.1.1"
        const val DEFAULT_WINDOW = 12

        /** Stable `probe.status` keys rendered and matched by schemes and the diagnostics page. */
        const val STATUS_IDLE = "idle"
        const val STATUS_PROBING = "probing"
        const val STATUS_OK = "ok"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_DNS_ERROR = "dns_error"
        const val STATUS_UNREACHABLE = "unreachable"
        const val STATUS_UNSUPPORTED = "unsupported"
        const val STATUS_DISABLED = "disabled"

        private const val PING_LATENCY_MS = "ping.latency_ms"
        private const val PING_LOSS_PERCENT = "ping.loss_percent"
        private const val PING_SENT = "ping.sent"
        private const val PING_RECEIVED = "ping.received"
        private const val PING_JITTER_MS = "ping.jitter_ms"
        private const val PING_TARGET = "ping.target"
        private const val PING_STATUS = "ping.status"
        private const val PING_STATUS_TEXT = "ping.status_text"

        private const val MIN_TIMEOUT_MS = 250
        private const val MAX_TIMEOUT_MS = 30_000
        private const val MAX_WINDOW = 200

        private val TEXT_ZH = mapOf(
            STATUS_IDLE to "等待检测",
            STATUS_PROBING to "检测中",
            STATUS_OK to "在线",
            STATUS_TIMEOUT to "超时",
            STATUS_DNS_ERROR to "域名解析失败",
            STATUS_UNREACHABLE to "不可达",
            STATUS_UNSUPPORTED to "不支持",
            STATUS_DISABLED to "已禁用",
        )

        private val TEXT_EN = mapOf(
            STATUS_IDLE to "Waiting",
            STATUS_PROBING to "Probing",
            STATUS_OK to "Online",
            STATUS_TIMEOUT to "Timed out",
            STATUS_DNS_ERROR to "DNS lookup failed",
            STATUS_UNREACHABLE to "Unreachable",
            STATUS_UNSUPPORTED to "Not supported",
            STATUS_DISABLED to "Disabled",
        )

        /** Localized short status for [statusKey]; unknown keys degrade to the raw key. */
        fun statusText(statusKey: String, english: Boolean): String =
            (if (english) TEXT_EN else TEXT_ZH)[statusKey] ?: statusKey
    }
}
