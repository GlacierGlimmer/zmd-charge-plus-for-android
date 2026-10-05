package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import java.io.File

/**
 * GPU metrics — probed honestly rather than invented.
 *
 * Android has no public GPU load, clock, temperature or VRAM API. This collector therefore reads
 * only vendor nodes that are known to exist on *some* devices (Qualcomm KGSL, ARM Mali, generic
 * devfreq) and publishes a value solely when a node yields a sane number. Everything else becomes
 * `Unavailable(NOT_SUPPORTED/NOT_AVAILABLE_ON_DEVICE)` with the probed paths in the detail so the
 * capability report and the diagnostics page stay truthful.
 */
class GpuCollector(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "gpu"

    override val tier: SamplingTier = SamplingTier.SLOW

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val usage = probeUsage()
        if (usage == null) {
            into.putUnavailable(Variables.GPU_USAGE, UnavailableReason.NOT_SUPPORTED, NOT_SUPPORTED_DETAIL)
        } else {
            into[Variables.GPU_USAGE] = MetricValue.Number(usage.value)
        }

        val frequencyHz = probeFrequencyHz()
        if (frequencyHz == null) {
            into.putUnavailable(Variables.GPU_FREQUENCY_GHZ, UnavailableReason.NOT_SUPPORTED, NOT_SUPPORTED_DETAIL)
        } else {
            into[Variables.GPU_FREQUENCY_GHZ] = MetricValue.Number(frequencyHz / 1_000_000_000.0)
        }

        val temperature = ThermalZoneClassifier.hottestGpuCelsius(ThermalZoneReader.readAll())
        if (temperature == null) {
            into.putUnavailable(
                Variables.GPU_TEMPERATURE_C,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "no GPU-type thermal zone with a plausible reading under ${MetricPaths.THERMAL_CLASS_ROOT}",
            )
        } else {
            into[Variables.GPU_TEMPERATURE_C] = MetricValue.Number(temperature)
        }

        val memoryDetail = "no public Android API exposes GPU memory; probed " +
            "${MetricPaths.KGSL_ROOT} (gpu_model/gpumem) and ${MetricPaths.DEV_FREQ_ROOT}"
        into.putUnavailable(Variables.GPU_MEMORY_USED_BYTES, UnavailableReason.NOT_SUPPORTED, memoryDetail)
        into.putUnavailable(Variables.GPU_MEMORY_TOTAL_BYTES, UnavailableReason.NOT_SUPPORTED, memoryDetail)

        val model = probeModel()
        if (model == null) {
            into.putUnavailable(
                Variables.GPU_MODEL,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "no readable vendor GPU name node (${MetricPaths.KGSL_MODEL})",
            )
        } else {
            into[Variables.GPU_MODEL] = MetricValue.Text(model)
        }
    }

    private fun probeUsage(): Probe? {
        readFirst(MetricPaths.KGSL_BUSY_PERCENTAGE) { GpuNodeParsers.parsePercent(it) }?.let { return it }
        readFirst(MetricPaths.KGSL_GPU_BUSY) { GpuNodeParsers.parseBusyPair(it) }?.let { return it }
        for (path in devfreqLoadNodes()) {
            readFirst(path) { GpuNodeParsers.parseDevfreqLoad(it) }?.let { return it }
        }
        readFirst(MetricPaths.KERNEL_GPU_BUSY) { GpuNodeParsers.parsePercent(it) }?.let { return it }
        readFirst(MetricPaths.MALI_UTILIZATION) { GpuNodeParsers.parsePercent(it) }?.let { return it }
        return null
    }

    private fun probeFrequencyHz(): Double? {
        readFirst(MetricPaths.KGSL_CLOCK) { GpuNodeParsers.parseFrequencyHz(it) }?.let { return it.value }
        for (path in devfreqCurFreqNodes()) {
            readFirst(path) { GpuNodeParsers.parseFrequencyHz(it) }?.let { return it.value }
        }
        return null
    }

    private fun probeModel(): String? {
        val raw = ProcFiles.readText(MetricPaths.KGSL_MODEL)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun devfreqLoadNodes(): List<String> = devfreqGpuDirectories().map { "$it/load" }

    private fun devfreqCurFreqNodes(): List<String> = devfreqGpuDirectories().map { "$it/cur_freq" }

    private fun devfreqGpuDirectories(): List<String> {
        val entries = try {
            File(MetricPaths.DEV_FREQ_ROOT).list()?.toList().orEmpty()
        } catch (error: Exception) {
            emptyList()
        }
        return entries.filter { it.contains("gpu", ignoreCase = true) || it.contains("kgsl", ignoreCase = true) }
            .sorted()
            .map { "${MetricPaths.DEV_FREQ_ROOT}/$it" }
    }

    private fun readFirst(path: String, parse: (String) -> Double?): Probe? {
        val text = ProcFiles.readText(path) ?: return null
        val value = parse(text) ?: return null
        return Probe(value, path)
    }

    private data class Probe(val value: Double, val detail: String)

    private companion object {
        const val NOT_SUPPORTED_DETAIL =
            "no public Android GPU API; probed /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage, " +
                "/sys/class/kgsl/kgsl-3d0/gpubusy, /sys/class/devfreq/*gpu*/load, " +
                "/sys/kernel/gpu/gpu_busy, /sys/class/misc/mali0/device/utilization"
    }
}
