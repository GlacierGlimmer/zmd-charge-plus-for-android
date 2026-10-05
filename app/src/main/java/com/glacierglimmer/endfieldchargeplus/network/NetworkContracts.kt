package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import kotlinx.serialization.json.JsonElement

/** Result of one probe attempt against one target. */
data class ProbeResult(
    val timestampMs: Long,
    val target: String,
    val protocol: ProbeProtocol,
    val success: Boolean,
    val latencyMs: Double?,
    val timeout: Boolean = false,
    val error: String? = null,
    /** True when the probe was answered from a cached/unavailable path instead of the network. */
    val skipped: Boolean = false,
)

/**
 * A single packet probe. Implementations must use only public Android APIs:
 * `InetAddress.isReachable` for ICMP-style checks, `Socket`/`DatagramSocket` for TCP/UDP.
 */
interface NetworkProbe {
    suspend fun probe(
        target: String,
        protocol: ProbeProtocol,
        port: Int,
        timeoutMs: Int,
    ): ProbeResult

    /** True when a probe of this protocol is expected to work on this device/network. */
    fun isProtocolLikelySupported(protocol: ProbeProtocol): Boolean
}

/**
 * Rolling statistics for the packet probe HUD.
 *
 * Loss is computed over the most recent [windowSize] attempts, latency is the arithmetic mean of the
 * successful ones, jitter is the mean absolute difference between consecutive successful samples.
 */
class ProbeStatistics(private val windowSize: Int = 12) {

    private val samples = ArrayDeque<ProbeResult>()

    @Synchronized
    fun record(result: ProbeResult) {
        if (result.skipped) return
        samples.addLast(result)
        while (samples.size > windowSize) samples.removeFirst()
    }

    @Synchronized
    fun reset() = samples.clear()

    @Synchronized
    fun sent(): Int = samples.size

    @Synchronized
    fun received(): Int = samples.count { it.success }

    @Synchronized
    fun lossPercent(): Double {
        val total = samples.size
        if (total == 0) return 0.0
        return (total - received()) * 100.0 / total
    }

    @Synchronized
    fun averageLatencyMs(): Double? {
        val latencies = samples.mapNotNull { if (it.success) it.latencyMs else null }
        return if (latencies.isEmpty()) null else latencies.average()
    }

    @Synchronized
    fun jitterMs(): Double? {
        val latencies = samples.mapNotNull { if (it.success) it.latencyMs else null }
        if (latencies.size < 2) return null
        var total = 0.0
        for (index in 1 until latencies.size) {
            total += kotlin.math.abs(latencies[index] - latencies[index - 1])
        }
        return total / (latencies.size - 1)
    }

    @Synchronized
    fun last(): ProbeResult? = samples.lastOrNull()
}

/** Minimal HTTP GET/JSON client used by the custom HTTP/JSON data sources. */
interface HttpJsonClient {
    suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): Result<JsonElement>

    /** Last transport error, for the diagnostics page. */
    fun lastError(): String?
}
