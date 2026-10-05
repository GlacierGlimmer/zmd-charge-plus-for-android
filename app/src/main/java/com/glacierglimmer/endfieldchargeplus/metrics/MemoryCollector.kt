package com.glacierglimmer.endfieldchargeplus.metrics

import android.app.ActivityManager
import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * Physical memory.
 *
 * `total`/`available` come from the supported `ActivityManager.MemoryInfo` API and fall back to
 * `/proc/meminfo` when that service is unavailable; `used`/`usage` are derived from them exactly
 * like the Windows edition (`used = total - available`). The detail fields (`cached`, `free`,
 * `swap`) are only published when `/proc/meminfo` really exposes them — never as a fabricated 0.
 *
 * `memory.low` is `1`/`0` from `MemoryInfo.lowMemory` (booleans are published as numbers so colour
 * rules and templates can compare them).
 */
class MemoryCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "memory"

    override val tier: SamplingTier = SamplingTier.NORMAL

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = activityManager?.let { manager ->
            runCatching { ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }.getOrNull()
        }
        val memInfo = ProcFiles.readText(MetricPaths.PROC_MEMINFO)?.let(MemInfoParser::parse)

        val total = memoryInfo?.totalMem?.takeIf { it > 0L } ?: memInfo?.totalBytes
        if (total == null || total <= 0L) {
            val detail = "no memory total: ActivityManager.MemoryInfo.totalMem and " +
                "${MetricPaths.PROC_MEMINFO} MemTotal are both unreadable"
            into.putUnavailable(Variables.MEMORY_TOTAL_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_AVAILABLE_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_USED_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_USAGE, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        } else {
            into[Variables.MEMORY_TOTAL_BYTES] = MetricValue.Number(total.toDouble())
            val available = memoryInfo?.availMem?.takeIf { it > 0L } ?: memInfo?.availableBytes
            if (available == null) {
                val detail = "no available memory: ActivityManager.MemoryInfo.availMem and " +
                    "${MetricPaths.PROC_MEMINFO} MemAvailable are both unreadable"
                into.putUnavailable(Variables.MEMORY_AVAILABLE_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
                into.putUnavailable(Variables.MEMORY_USED_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
                into.putUnavailable(Variables.MEMORY_USAGE, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            } else {
                val used = (total - available).coerceAtLeast(0L)
                into[Variables.MEMORY_AVAILABLE_BYTES] = MetricValue.Number(available.toDouble())
                into[Variables.MEMORY_USED_BYTES] = MetricValue.Number(used.toDouble())
                into[Variables.MEMORY_USAGE] =
                    MetricValue.Number((used.toDouble() * 100.0 / total.toDouble()).coerceIn(0.0, 100.0))
            }
        }

        if (memoryInfo == null) {
            into.putUnavailable(
                Variables.MEMORY_LOW,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "ActivityManager.MemoryInfo unavailable; lowMemory flag cannot be read",
            )
        } else {
            into[Variables.MEMORY_LOW] = MetricValue.Number(if (memoryInfo.lowMemory) 1.0 else 0.0)
        }

        if (memInfo == null) {
            val detail = "${MetricPaths.PROC_MEMINFO} is not readable"
            into.putUnavailable(Variables.MEMORY_CACHED_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_FREE_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_SWAP_TOTAL_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.MEMORY_SWAP_USED_BYTES, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        } else {
            into.publishMemInfoField(Variables.MEMORY_CACHED_BYTES, "Cached", memInfo.cachedBytes)
            into.publishMemInfoField(Variables.MEMORY_FREE_BYTES, "MemFree", memInfo.freeBytes)
            into.publishMemInfoField(Variables.MEMORY_SWAP_TOTAL_BYTES, "SwapTotal", memInfo.swapTotalBytes)
            if (memInfo.swapUsedBytes == null) {
                into.putUnavailable(
                    Variables.MEMORY_SWAP_USED_BYTES,
                    UnavailableReason.NO_DATA,
                    "SwapTotal/SwapFree not both present in ${MetricPaths.PROC_MEMINFO}",
                )
            } else {
                into.publishMemInfoField(Variables.MEMORY_SWAP_USED_BYTES, "SwapTotal-SwapFree", memInfo.swapUsedBytes)
            }
        }
    }

    private fun MutableMap<String, MetricValue>.publishMemInfoField(name: String, field: String, value: Long?) {
        if (value == null) {
            putUnavailable(
                name,
                UnavailableReason.NO_DATA,
                "$field is not present in ${MetricPaths.PROC_MEMINFO}",
            )
        } else {
            this[name] = MetricValue.Number(value.toDouble())
        }
    }
}
