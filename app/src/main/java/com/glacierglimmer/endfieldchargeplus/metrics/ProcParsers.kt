package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import java.io.File

/**
 * Canonical kernel paths probed by the Android collectors.
 *
 * They are constants (not computed) so that ability reports and diagnostics can name the exact
 * node that was actually read, which is part of the "never fabricate data" contract.
 */
internal object MetricPaths {
    const val PROC_MEMINFO = "/proc/meminfo"
    const val PROC_STAT = "/proc/stat"
    const val PROC_CPUINFO = "/proc/cpuinfo"
    const val CPU_SYS_ROOT = "/sys/devices/system/cpu"
    const val THERMAL_CLASS_ROOT = "/sys/class/thermal"
    const val DEV_FREQ_ROOT = "/sys/class/devfreq"
    const val KGSL_ROOT = "/sys/class/kgsl/kgsl-3d0"
    const val KGSL_BUSY_PERCENTAGE = "$KGSL_ROOT/gpu_busy_percentage"
    const val KGSL_GPU_BUSY = "$KGSL_ROOT/gpubusy"
    const val KGSL_CLOCK = "$KGSL_ROOT/gpuclk"
    const val KGSL_MODEL = "$KGSL_ROOT/gpu_model"
    const val KERNEL_GPU_BUSY = "/sys/kernel/gpu/gpu_busy"
    const val MALI_UTILIZATION = "/sys/class/misc/mali0/device/utilization"

    fun scalingCurFreq(core: Int): String = "$CPU_SYS_ROOT/cpu$core/cpufreq/scaling_cur_freq"

    fun thermalZoneType(name: String): String = "$THERMAL_CLASS_ROOT/$name/type"

    fun thermalZoneTemp(name: String): String = "$THERMAL_CLASS_ROOT/$name/temp"
}

/** Why a single kernel node could not be read. */
internal sealed interface NodeReadResult {
    /** The node returned non-blank text. */
    data class Ok(val path: String, val text: String) : NodeReadResult

    /** The node was missing, empty, unreadable or raised; [reasonKey] is a localization key. */
    data class Failure(val path: String, val reasonKey: String, val detail: String) : NodeReadResult
}

/**
 * Reads a single kernel/sysfs node and reports *why* it failed.
 *
 * The failure kind matters for the capability report: a node that does not exist is a different
 * (and honest) statement from a node that exists but is blocked by SELinux.
 */
internal object NodeProbe {

    const val REASON_NODE_MISSING = "capability_node_missing"
    const val REASON_SELINUX_DENIED = "capability_selinux_denied"
    const val REASON_READ_FAILED = "capability_not_available_on_device"

    fun read(path: String, reader: KernelReader = FileKernelReader): NodeReadResult {
        reader.readText(path)?.takeIf { it.isNotBlank() }?.let { return NodeReadResult.Ok(path, it) }
        val file = File(path)
        if (!file.exists()) return NodeReadResult.Failure(path, REASON_NODE_MISSING, "node missing: $path")
        if (!file.canRead()) {
            return NodeReadResult.Failure(
                path,
                REASON_SELINUX_DENIED,
                "node exists but is not readable (SELinux/uid restriction): $path",
            )
        }
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                NodeReadResult.Failure(path, REASON_NODE_MISSING, "node present but empty: $path")
            } else {
                NodeReadResult.Ok(path, text)
            }
        } catch (error: SecurityException) {
            NodeReadResult.Failure(
                path,
                REASON_SELINUX_DENIED,
                "node read raised SecurityException: $path",
            )
        } catch (error: Exception) {
            NodeReadResult.Failure(
                path,
                REASON_READ_FAILED,
                "node read failed (${error.javaClass.simpleName}): $path",
            )
        }
    }
}

/** Thin, allocation-light accessor for the files the collectors read. */
internal object ProcFiles {

    fun readText(path: String): String? =
        try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) null else file.readText()
        } catch (error: Exception) {
            null
        }

    fun readLong(path: String): Long? = readText(path)?.trim()?.toLongOrNull()

    fun readLines(path: String): List<String>? = readText(path)?.lines()

    fun listNames(directory: String): List<String> =
        try {
            File(directory).list()?.toList().orEmpty()
        } catch (error: Exception) {
            emptyList()
        }
}

/** Parsed `/proc/meminfo` values in bytes; `null` means the field is absent or unparsable. */
internal data class MemInfo(
    val totalBytes: Long? = null,
    val availableBytes: Long? = null,
    val freeBytes: Long? = null,
    val cachedBytes: Long? = null,
    val swapTotalBytes: Long? = null,
    val swapFreeBytes: Long? = null,
) {
    /** `SwapTotal - SwapFree` when both fields exist; a real `0` is kept as `0`. */
    val swapUsedBytes: Long?
        get() = if (swapTotalBytes != null && swapFreeBytes != null) {
            (swapTotalBytes - swapFreeBytes).coerceAtLeast(0L)
        } else {
            null
        }
}

/**
 * `/proc/meminfo` parser.
 *
 * Values are reported in kB by the kernel and converted to bytes (×1024). Only fields that are
 * actually present are returned; nothing is defaulted to zero.
 */
internal object MemInfoParser {

    private const val KILOBYTE = 1024L

    fun parse(text: String): MemInfo {
        val fields = LinkedHashMap<String, Long>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().substringBefore(' ').toLongOrNull()
            if (value != null) fields[key] = value
        }
        return MemInfo(
            totalBytes = fields["MemTotal"]?.times(KILOBYTE),
            availableBytes = fields["MemAvailable"]?.times(KILOBYTE),
            freeBytes = fields["MemFree"]?.times(KILOBYTE),
            cachedBytes = fields["Cached"]?.times(KILOBYTE),
            swapTotalBytes = fields["SwapTotal"]?.times(KILOBYTE),
            swapFreeBytes = fields["SwapFree"]?.times(KILOBYTE),
        )
    }
}

/** Aggregate CPU time counters from one `/proc/stat` line, in USER_HZ ticks. */
internal data class CpuTicks(val total: Long, val idle: Long)

/** One `/proc/stat` reading: the aggregate line, the per-core lines and the misc counters. */
internal data class ProcStatSnapshot(
    val totalCpu: CpuTicks? = null,
    val cores: Map<Int, CpuTicks> = emptyMap(),
    val contextSwitches: Long? = null,
    val interrupts: Long? = null,
)

/**
 * `/proc/stat` parser following the ECP/Linux convention: the first eight numeric columns are
 * used (guest/guest_nice are excluded) and idle is `idle + iowait`.
 */
internal object ProcStatParser {

    fun parse(text: String): ProcStatSnapshot {
        var totalCpu: CpuTicks? = null
        val cores = LinkedHashMap<Int, CpuTicks>()
        var contextSwitches: Long? = null
        var interrupts: Long? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> Unit
                parts[0] == "ctxt" -> contextSwitches = parts.getOrNull(1)?.toLongOrNull()
                parts[0] == "intr" -> interrupts = parts.getOrNull(1)?.toLongOrNull()
                parts[0] == "cpu" -> totalCpu = parseTicks(parts)
                parts[0].startsWith("cpu") -> {
                    val index = parts[0].removePrefix("cpu").toIntOrNull()
                    val ticks = if (index == null) null else parseTicks(parts)
                    if (index != null && ticks != null) cores[index] = ticks
                }
            }
        }
        return ProcStatSnapshot(totalCpu, cores, contextSwitches, interrupts)
    }

    private fun parseTicks(parts: List<String>): CpuTicks? {
        val tokens = parts.drop(1).take(8)
        if (tokens.size < 4) return null
        val values = tokens.map { it.toLongOrNull() ?: return null }
        val idle = values[3] + values.getOrElse(4) { 0L }
        return CpuTicks(total = values.sum(), idle = idle)
    }
}

/** Two-sample CPU load arithmetic; a rate is never produced from a single sample. */
internal object CpuUsageMath {

    /**
     * Busy percentage between two monotonic samples, or `null` when the sample pair is unusable
     * (counter reset, zero elapsed ticks).
     */
    fun usagePercent(previous: CpuTicks, current: CpuTicks): Double? {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L) return null
        val busy = (totalDelta - idleDelta).coerceIn(0L, totalDelta)
        return (busy.toDouble() * 100.0 / totalDelta.toDouble()).coerceIn(0.0, 100.0)
    }
}

/** `/proc/cpuinfo` model extraction, in ECP precedence order. */
internal object CpuInfoParser {

    fun model(text: String): String? {
        val fields = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (value.isNotEmpty() && !fields.containsKey(key)) fields[key] = value
        }
        // On ARM `/proc/cpuinfo` the `processor` line is the core index (a bare number), so a
        // numeric "model" is rejected instead of being published as the CPU name.
        return fields["hardware"]
            ?: fields["model name"]
            ?: fields["processor"]?.takeIf { value -> value.any(Char::isLetter) }
    }
}

/** One thermal zone as exposed by `/sys/class/thermal`. */
internal data class ThermalZone(val type: String, val rawMilliCelsius: Long?, val path: String)

/**
 * Thermal zone classification.
 *
 * Android commonly exposes dozens of zones; only zones whose `type` clearly names a CPU (or a GPU)
 * are trusted, and only physically plausible temperatures are accepted.
 */
internal object ThermalZoneClassifier {

    private val CPU_TOKENS = listOf(
        "cpu", "tsens", "mtktscpu", "soc_thermal", "soc-thermal", "cpu-therm", "cpu_therm",
        "bigcore", "littlecore", "cluster", "ap_thermal", "ap-therm",
    )

    private val GPU_TOKENS = listOf("gpu", "kgsl", "mali", "adreno")

    fun isCpuZone(type: String): Boolean {
        val normalized = type.lowercase().trim()
        return CPU_TOKENS.any { normalized.contains(it) }
    }

    fun isGpuZone(type: String): Boolean {
        val normalized = type.lowercase().trim()
        return GPU_TOKENS.any { normalized.contains(it) }
    }

    /**
     * Kernel thermal zones report milli-Celsius. A non-positive raw value is the kernel's
     * "not initialised" placeholder (unreadable sensor), and implausible values are rejected
     * rather than clamped.
     */
    fun celsius(rawMilliCelsius: Long): Double? {
        if (rawMilliCelsius <= 0L) return null
        val value = rawMilliCelsius / 1000.0
        return if (value in 0.0..150.0) value else null
    }

    /** Hottest CPU-type zone with a plausible reading, or `null` when no zone qualifies. */
    fun hottestCpuCelsius(zones: List<ThermalZone>): Double? =
        zones.filter { isCpuZone(it.type) }
            .mapNotNull { zone -> zone.rawMilliCelsius?.let(::celsius) }
            .maxOrNull()

    /** Hottest GPU-type zone with a plausible reading, or `null` when no zone qualifies. */
    fun hottestGpuCelsius(zones: List<ThermalZone>): Double? =
        zones.filter { isGpuZone(it.type) }
            .mapNotNull { zone -> zone.rawMilliCelsius?.let(::celsius) }
            .maxOrNull()
}

/** CPU frequency arithmetic for `/sys/.../cpufreq/scaling_cur_freq` (kHz). */
internal object CpuFrequencyMath {

    /**
     * Average of the readable, positive per-core current frequencies in kHz.
     *
     * Unreadable cores are excluded instead of being treated as 0 MHz, so a partially restricted
     * device still gets an honest average over the cores Android actually exposes.
     */
    fun averageKhz(values: Iterable<Long?>): Double? {
        val readable = values.filterNotNull().filter { it > 0L }
        if (readable.isEmpty()) return null
        return readable.average()
    }

    fun megahertz(khz: Double): Double = khz / 1000.0
}

/** Parsers for the vendor GPU nodes that exist on some devices (Qualcomm KGSL, ARM Mali, ...). */
internal object GpuNodeParsers {

    /** `"12"`, `"12 %"`, `"12%"` → 12.0; anything outside 0..100 is rejected. */
    fun parsePercent(raw: String): Double? {
        val token = raw.trim().split(' ', '\t').firstOrNull { it.isNotBlank() } ?: return null
        val value = token.removeSuffix("%").toDoubleOrNull() ?: return null
        return value.takeIf { it in 0.0..100.0 }
    }

    /** KGSL `gpubusy` is `"<busy> <total>"`; the ratio is the load percentage. */
    fun parseBusyPair(raw: String): Double? {
        val parts = raw.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val busy = parts[0].toDoubleOrNull() ?: return null
        val total = parts[1].toDoubleOrNull() ?: return null
        if (busy < 0.0 || total <= 0.0) return null
        return (busy / total * 100.0).coerceIn(0.0, 100.0)
    }

    /** devfreq `load` is `"<percent>@<freq>Hz"` on most kernels. */
    fun parseDevfreqLoad(raw: String): Double? =
        parsePercent(raw.substringBefore('@'))

    /** devfreq/KGSL clock values are Hz, sometimes suffixed with `Hz`. */
    fun parseFrequencyHz(raw: String): Double? {
        val token = raw.trim().split(' ', '\t').firstOrNull { it.isNotBlank() } ?: return null
        val value = token.removeSuffix("Hz").toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 }
    }
}

/** Monotonic-counter rate arithmetic shared by the network and battery collectors. */
internal object TrafficRateMath {

    /**
     * Bytes per second for monotonic counters.
     *
     * Returns `null` when the sample must not produce a rate: a single sample, a counter reset
     * (`current < previous`, for example after a reboot) or a non-positive elapsed interval.
     */
    fun rateBps(previousBytes: Long, currentBytes: Long, elapsedMs: Long): Double? {
        if (previousBytes < 0L || currentBytes < 0L) return null
        if (elapsedMs <= 0L) return null
        if (currentBytes < previousBytes) return null
        return (currentBytes - previousBytes).toDouble() * 1000.0 / elapsedMs.toDouble()
    }
}

/** Writes an honest "no value" into a snapshot map, with a reason the diagnostics page can show. */
internal fun MutableMap<String, MetricValue>.putUnavailable(
    name: String,
    reason: UnavailableReason,
    detail: String = "",
) {
    this[name] = MetricValue.Unavailable(reason, detail)
}

/** Writes [MetricValue.Number], or `NoData` when the value is absent/NaN/infinite. */
internal fun MutableMap<String, MetricValue>.putNumber(name: String, value: Double?) {
    this[name] = MetricValue.number(value)
}

/** Writes [MetricValue.Text], or `NoData` when the text is blank. */
internal fun MutableMap<String, MetricValue>.putText(name: String, value: String?) {
    this[name] = MetricValue.text(value)
}
