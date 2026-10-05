package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.os.Build
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import java.io.File

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
) : MetricCollector {

    override val id: String = "cpu"

    override val tier: SamplingTier = SamplingTier.NORMAL

    private var previous: ProcStatSnapshot? = null

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        collectUsage(into)
        collectFrequency(into)
        collectTemperature(into)
        collectIdentity(into)
    }

    private fun collectUsage(into: MutableMap<String, MetricValue>) {
        val text = ProcFiles.readText(MetricPaths.PROC_STAT)
        if (text == null) {
            val detail = "${MetricPaths.PROC_STAT} is not readable"
            into.putUnavailable(Variables.CPU_USAGE, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            return
        }
        val snapshot = ProcStatParser.parse(text)
        val previousSnapshot = previous
        previous = snapshot

        val current = snapshot.totalCpu
        val previousTicks = previousSnapshot?.totalCpu
        when {
            current == null -> into.putUnavailable(
                Variables.CPU_USAGE,
                UnavailableReason.NO_DATA,
                "${MetricPaths.PROC_STAT} has no aggregate cpu line",
            )

            previousTicks == null -> into.putUnavailable(
                Variables.CPU_USAGE,
                UnavailableReason.NO_DATA,
                "first ${MetricPaths.PROC_STAT} sample; a CPU rate requires two samples",
            )

            else -> {
                val usage = CpuUsageMath.usagePercent(previousTicks, current)
                if (usage == null) {
                    into.putUnavailable(
                        Variables.CPU_USAGE,
                        UnavailableReason.NO_DATA,
                        "CPU tick counters reset or did not advance; re-priming",
                    )
                } else {
                    into[Variables.CPU_USAGE] = MetricValue.Number(usage)
                }
            }
        }

        for ((index, ticks) in snapshot.cores) {
            val name = "${Variables.CPU_PER_CORE_PREFIX}$index.usage"
            val corePrevious = previousSnapshot?.cores?.get(index)
            if (corePrevious == null) {
                into.putUnavailable(name, UnavailableReason.NO_DATA, "first sample for cpu$index")
                continue
            }
            val usage = CpuUsageMath.usagePercent(corePrevious, ticks)
            if (usage == null) {
                into.putUnavailable(name, UnavailableReason.NO_DATA, "cpu$index counters reset; re-priming")
            } else {
                into[name] = MetricValue.Number(usage)
            }
        }
    }

    private fun collectFrequency(into: MutableMap<String, MetricValue>) {
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val nodes = (0 until coreCount).map { MetricPaths.scalingCurFreq(it) }
        val readings = nodes.map { ProcFiles.readLong(it) }
        val averageKhz = CpuFrequencyMath.averageKhz(readings)
        if (averageKhz == null) {
            val detail = "no readable cpufreq scaling_cur_freq node among $coreCount cores " +
                "(probed ${nodes.firstOrNull() ?: MetricPaths.CPU_SYS_ROOT})"
            into.putUnavailable(Variables.CPU_FREQUENCY_MHZ, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.CPU_FREQUENCY_GHZ, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            return
        }
        val megahertz = CpuFrequencyMath.megahertz(averageKhz)
        into[Variables.CPU_FREQUENCY_MHZ] = MetricValue.Number(megahertz)
        into[Variables.CPU_FREQUENCY_GHZ] = MetricValue.Number(megahertz / 1000.0)
    }

    private fun collectTemperature(into: MutableMap<String, MetricValue>) {
        val zones = ThermalZoneReader.readAll()
        val celsius = ThermalZoneClassifier.hottestCpuCelsius(zones)
        if (celsius == null) {
            val detail = "no CPU-type thermal zone with a plausible reading under " +
                "${MetricPaths.THERMAL_CLASS_ROOT} (zones=${zones.size})"
            into.putUnavailable(Variables.CPU_TEMPERATURE_C, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        } else {
            into[Variables.CPU_TEMPERATURE_C] = MetricValue.Number(celsius)
        }
    }

    private fun collectIdentity(into: MutableMap<String, MetricValue>) {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores > 0) {
            into[Variables.CPU_CORES] = MetricValue.Number(cores.toDouble())
        } else {
            into.putUnavailable(Variables.CPU_CORES, UnavailableReason.NO_DATA, "availableProcessors() returned 0")
        }

        val cpuInfo = ProcFiles.readText(MetricPaths.PROC_CPUINFO)
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

    fun readAll(): List<ThermalZone> {
        val names = try {
            File(MetricPaths.THERMAL_CLASS_ROOT).list()?.toList().orEmpty()
        } catch (error: Exception) {
            emptyList()
        }
        return names.filter { it.startsWith("thermal_zone") }.sorted().map { name ->
            val type = ProcFiles.readText(MetricPaths.thermalZoneType(name))?.trim().orEmpty()
            val raw = ProcFiles.readLong(MetricPaths.thermalZoneTemp(name))
            ThermalZone(type = type, rawMilliCelsius = raw, path = MetricPaths.thermalZoneTemp(name))
        }.filter { it.type.isNotEmpty() }
    }
}
