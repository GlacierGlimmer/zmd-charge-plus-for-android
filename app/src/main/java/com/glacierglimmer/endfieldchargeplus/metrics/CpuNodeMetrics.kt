package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.os.Build
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import java.io.File

/** Actual kernel sampling, shared by production and fixture tests; root is supplied by the reader. */
internal class CpuNodeMetrics(private val reader: KernelReader, private val coreCount: () -> Int) {
    private var previous: ProcStatSnapshot? = null
    fun collect(into: MutableMap<String, MetricValue>) {
        collectUsage(into)
        collectFrequency(into)
        collectTemperature(into)
    }
    private fun collectUsage(into: MutableMap<String, MetricValue>) {
        val text = reader.readText(MetricPaths.PROC_STAT)
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

        for (index in previousSnapshot?.cores.orEmpty().keys - snapshot.cores.keys) {
            into.putUnavailable("${Variables.CPU_PER_CORE_PREFIX}$index.usage", UnavailableReason.NO_DATA, "cpu$index is offline")
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
        val cores = reader.listNames(MetricPaths.CPU_SYS_ROOT).mapNotNull { Regex("cpu([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.sorted().takeIf { it.isNotEmpty() } ?: (0 until coreCount()).toList()
        val coreCount = cores.size
        val nodes = cores.map { MetricPaths.scalingCurFreq(it) }
        val readings = nodes.map { reader.readLong(it) }
        val averageKhz = CpuFrequencyMath.averageKhz(readings)
        val maxKhz = cores.mapNotNull { reader.readLong("${MetricPaths.CPU_SYS_ROOT}/cpu$it/cpufreq/cpuinfo_max_freq") }.filter { it > 0 }.maxOrNull()
        into.putNumber(Variables.CPU_MAX_FREQUENCY_GHZ, maxKhz?.div(1_000_000.0))
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
        val zones = ThermalZoneReader.readAll(reader)
        val celsius = ThermalZoneClassifier.hottestCpuCelsius(zones)
        if (celsius == null) {
            val detail = "no CPU-type thermal zone with a plausible reading under " +
                "${MetricPaths.THERMAL_CLASS_ROOT} (zones=${zones.size})"
            into.putUnavailable(Variables.CPU_TEMPERATURE_C, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        } else {
            into[Variables.CPU_TEMPERATURE_C] = MetricValue.Number(celsius)
        }
    }

}
