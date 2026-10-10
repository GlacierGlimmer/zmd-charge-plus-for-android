package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.os.Build
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * CPU load, frequency, temperature and identity.
 *
 * There is no public Android API for system CPU load, so this collector reads `/proc/stat` with the
 * same convention as the Linux edition: the first eight columns (guest/guest_nice excluded) and
 * `idle = idle + iowait`. A rate needs two monotonic samples, so the very first pass deliberately
 * publishes `NoData` instead of a fabricated value, and a counter reset re-primes rather than
 * producing a nonsense delta.
 *
 * CPU temperature is only published when a thermal zone whose `type` clearly identifies a CPU
 * yields a plausible reading; on most retail devices SELinux hides those nodes and the variable
 * becomes unavailable with the probed path in the detail text.
 */
class CpuCollector(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
    private val reader: KernelReader = FileKernelReader,
) : MetricCollector {

    override val id: String = "cpu"

    override val tier: SamplingTier = SamplingTier.NORMAL

    private val nodes = CpuNodeMetrics(reader) { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        nodes.collect(into)
        collectIdentity(into)
    }

    private fun collectIdentity(into: MutableMap<String, MetricValue>) {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores > 0) {
            into[Variables.CPU_CORES] = MetricValue.Number(cores.toDouble())
        } else {
            into.putUnavailable(Variables.CPU_CORES, UnavailableReason.NO_DATA, "availableProcessors() returned 0")
        }

        val cpuInfo = reader.readText(MetricPaths.PROC_CPUINFO)
        val model = cpuInfo?.let(CpuInfoParser::model)
            ?: socModelFallback()
        if (model == null) {
            into.putUnavailable(
                Variables.CPU_MODEL,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "no Hardware/model name/Processor line in ${MetricPaths.PROC_CPUINFO}",
            )
        } else {
            into[Variables.CPU_MODEL] = MetricValue.Text(model)
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        if (abi == null) {
            into.putUnavailable(Variables.CPU_ABI, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, "Build.SUPPORTED_ABIS is empty")
        } else {
            into[Variables.CPU_ABI] = MetricValue.Text(abi)
        }
    }

    /** `/proc/cpuinfo` fallback: Android 12+ exposes the SoC model, older releases the board name. */
    private fun socModelFallback(): String? {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        return soc?.takeIf { it.isNotBlank() } ?: Build.HARDWARE.takeIf { it.isNotBlank() }
    }
}

/** Shared thermal zone reader used by the CPU and GPU collectors. */
internal object ThermalZoneReader {

    fun readAll(reader: KernelReader = FileKernelReader): List<ThermalZone> =
        reader.listNames(MetricPaths.THERMAL_CLASS_ROOT).filter { it.matches(Regex("thermal_zone[0-9]+")) }.sorted().map { name ->
            val type = reader.readText(MetricPaths.thermalZoneType(name))?.trim().orEmpty()
            val raw = reader.readLong(MetricPaths.thermalZoneTemp(name))
            ThermalZone(type, raw, MetricPaths.thermalZoneTemp(name))
        }.filter { it.type.isNotEmpty() }
}
