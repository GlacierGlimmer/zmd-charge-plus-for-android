package com.glacierglimmer.endfieldchargeplus.hardware

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.glacierglimmer.endfieldchargeplus.metrics.Capability
import com.glacierglimmer.endfieldchargeplus.metrics.CpuFrequencyMath
import com.glacierglimmer.endfieldchargeplus.metrics.CpuInfoParser
import com.glacierglimmer.endfieldchargeplus.metrics.GpuNodeParsers
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import com.glacierglimmer.endfieldchargeplus.metrics.MemInfoParser
import com.glacierglimmer.endfieldchargeplus.metrics.MetricPaths
import com.glacierglimmer.endfieldchargeplus.metrics.NodeProbe
import com.glacierglimmer.endfieldchargeplus.metrics.NodeReadResult
import com.glacierglimmer.endfieldchargeplus.metrics.ProcFiles
import com.glacierglimmer.endfieldchargeplus.metrics.ProcStatParser
import com.glacierglimmer.endfieldchargeplus.metrics.ThermalZone
import com.glacierglimmer.endfieldchargeplus.metrics.ThermalZoneClassifier
import com.glacierglimmer.endfieldchargeplus.metrics.ThermalZoneReader
import com.glacierglimmer.endfieldchargeplus.metrics.KernelReader
import com.glacierglimmer.endfieldchargeplus.metrics.FileKernelReader

/** Probe verdicts for the three packet-probe protocols. */
data class ProbeSupport(
    val icmp: Capability,
    val tcp: Capability,
    val udp: Capability,
)

/**
 * Probes what this specific device and Android version actually allow an unprivileged app to read.
 *
 * Every verdict comes from a real probe of the exact node/API named in the `detail` string �?never
 * from a hardcoded assumption. This is the data behind the settings/diagnostics page and the
 * README table, so an optimistic "supported" here would become fabricated HUD data later.
 */
class HardwareCapabilityDetector(private val context: Context, private val reader: KernelReader = FileKernelReader) {

    /** Runs a full probe. Safe to call from a background dispatcher; it performs file I/O. */
    fun detect(): HardwareCapabilities {
        val probeSupport = detectProbeSupport()
        val procStat = NodeProbe.read(MetricPaths.PROC_STAT, reader)
        val thermalZones = ThermalZoneReader.readAll(reader)

        return HardwareCapabilities(
            cpuTotalUsage = cpuTotalCapability(procStat),
            cpuPerCoreUsage = cpuPerCoreCapability(procStat),
            cpuFrequency = cpuFrequencyCapability(),
            cpuTemperature = cpuTemperatureCapability(thermalZones),
            gpuUsage = gpuUsageCapability(),
            gpuFrequency = gpuFrequencyCapability(),
            gpuTemperature = gpuTemperatureCapability(thermalZones),
            gpuMemory = Capability.unsupported(
                REASON_NOT_SUPPORTED,
                "no public Android API exposes GPU memory; probed ${MetricPaths.KGSL_ROOT} and ${MetricPaths.DEV_FREQ_ROOT}",
            ),
            batteryCurrent = batteryPropertyCapability(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, "BATTERY_PROPERTY_CURRENT_NOW"),
            batteryTemperature = batteryExtraCapability(BatteryManager.EXTRA_TEMPERATURE, "EXTRA_TEMPERATURE"),
            batteryVoltage = batteryExtraCapability(BatteryManager.EXTRA_VOLTAGE, "EXTRA_VOLTAGE"),
            memoryDetail = memoryDetailCapability(),
            storage = storageCapability(),
            networkTraffic = networkTrafficCapability(),
            wifiSsid = wifiSsidCapability(),
            probeIcmp = probeSupport.icmp,
            probeTcp = probeSupport.tcp,
            probeUdp = probeSupport.udp,
            cpuModel = cpuModel(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(0),
            deviceModel = Build.MODEL ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            scannedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Probe-protocol support.
     *
     * TCP/UDP connect probes need only `INTERNET`; raw ICMP needs `CAP_NET_RAW`, which is not
     * grantable to a normal app, and `InetAddress.isReachable()` is documented as unreliable
     * (usually falling back to TCP port 7). ICMP is therefore reported unsupported.
     */
    fun detectProbeSupport(): ProbeSupport {
        val internetGranted = context.checkSelfPermission(Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED
        val socketDetail = if (internetGranted) {
            "java.net.Socket/DatagramSocket connect probe is available (INTERNET granted)"
        } else {
            "INTERNET permission is not granted, so no probe can run"
        }
        val socketCapability = if (internetGranted) {
            Capability(supported = true, detail = socketDetail)
        } else {
            Capability.unsupported(REASON_PERMISSION_MISSING, socketDetail)
        }
        return ProbeSupport(
            icmp = Capability.unsupported(
                REASON_NOT_SUPPORTED,
                "raw ICMP requires CAP_NET_RAW (not grantable to a normal app); " +
                    "InetAddress.isReachable() is documented as unreliable and typically falls back to TCP port 7",
            ),
            tcp = socketCapability,
            udp = socketCapability,
        )
    }

    private fun cpuTotalCapability(procStat: NodeReadResult): Capability = when (procStat) {
        is NodeReadResult.Failure -> Capability.unsupported(procStat.reasonKey, procStat.detail)
        is NodeReadResult.Ok -> {
            val snapshot = ProcStatParser.parse(procStat.text)
            if (snapshot.totalCpu == null) {
                Capability.unsupported(REASON_NOT_SUPPORTED, "read ${procStat.path} but it has no aggregate cpu line")
            } else {
                Capability(supported = true, detail = "read ${procStat.path}: aggregate cpu line parsed (two samples required for a rate)")
            }
        }
    }

    private fun cpuPerCoreCapability(procStat: NodeReadResult): Capability = when (procStat) {
        is NodeReadResult.Failure -> Capability.unsupported(procStat.reasonKey, procStat.detail)
        is NodeReadResult.Ok -> {
            val cores = ProcStatParser.parse(procStat.text).cores
            if (cores.isEmpty()) {
                Capability.unsupported(REASON_NOT_SUPPORTED, "read ${procStat.path} but it exposes no per-core cpuN lines")
            } else {
                Capability(supported = true, detail = "read ${procStat.path}: ${cores.size} cpuN lines (cpu0..cpu${cores.keys.max()})")
            }
        }
    }

    private fun cpuFrequencyCapability(): Capability {
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val nodes = (0 until coreCount).map { MetricPaths.scalingCurFreq(it) }
        val readings = nodes.map { reader.readLong(it) }
        val readable = readings.count { it != null && it > 0L }
        val average = CpuFrequencyMath.averageKhz(readings)
        return when {
            average == null -> Capability.unsupported(
                REASON_NODE_MISSING,
                "none of $coreCount cpufreq nodes is readable; probed ${nodes.firstOrNull()} (SELinux blocks cpufreq on many Android 10+ devices)",
            )

            readable < coreCount -> Capability(
                supported = true,
                detail = "partial: $readable of $coreCount cores expose a current frequency (probed ${MetricPaths.CPU_SYS_ROOT}/cpu*/cpufreq/scaling_cur_freq)",
            )

            else -> Capability(
                supported = true,
                detail = "read $readable/$coreCount cores at ${MetricPaths.CPU_SYS_ROOT}/cpu*/cpufreq/scaling_cur_freq",
            )
        }
    }

    private fun cpuTemperatureCapability(zones: List<ThermalZone>): Capability {
        val cpuZones = zones.filter { ThermalZoneClassifier.isCpuZone(it.type) }
        if (cpuZones.isEmpty()) {
            return Capability.unsupported(
                REASON_NODE_MISSING,
                "no CPU-type zone under ${MetricPaths.THERMAL_CLASS_ROOT} (zones probed: ${zones.size})",
            )
        }
        val readable = cpuZones.count { it.rawMilliCelsius != null && ThermalZoneClassifier.celsius(it.rawMilliCelsius) != null }
        if (readable == 0) {
            return Capability.unsupported(
                REASON_SELINUX_DENIED,
                "CPU-type zones found (${cpuZones.joinToString { it.type }}) but none yields a plausible temp value",
            )
        }
        return Capability(
            supported = true,
            detail = "read $readable CPU-type zones: ${cpuZones.joinToString { "${it.type} (${it.path})" }}",
        )
    }

    private fun gpuUsageCapability(): Capability {
        val candidates = buildList {
            add(MetricPaths.KGSL_BUSY_PERCENTAGE)
            add(MetricPaths.KGSL_GPU_BUSY)
            addAll(devfreqNode("load"))
            add(MetricPaths.KERNEL_GPU_BUSY)
            add(MetricPaths.MALI_UTILIZATION)
        }
        for (path in candidates) {
            val text = reader.readText(path) ?: continue
            val value = GpuNodeParsers.parsePercent(text)
                ?: GpuNodeParsers.parseBusyPair(text)
                ?: GpuNodeParsers.parseDevfreqLoad(text)
            if (value != null) {
                return Capability(supported = true, detail = "read $path (value=$value)")
            }
        }
        return Capability.unsupported(
            REASON_NOT_SUPPORTED,
            "no readable/sane vendor GPU load node; probed ${candidates.joinToString()}",
        )
    }

    private fun gpuFrequencyCapability(): Capability {
        val candidates = buildList {
            add(MetricPaths.KGSL_CLOCK)
            addAll(devfreqNode("cur_freq"))
        }
        for (path in candidates) {
            val text = reader.readText(path) ?: continue
            val value = GpuNodeParsers.parseFrequencyHz(text)
            if (value != null) {
                return Capability(supported = true, detail = "read $path (value=${value}Hz)")
            }
        }
        return Capability.unsupported(
            REASON_NOT_SUPPORTED,
            "no readable vendor GPU clock node; probed ${candidates.joinToString()}",
        )
    }

    private fun gpuTemperatureCapability(zones: List<ThermalZone>): Capability {
        val gpuZones = zones.filter { ThermalZoneClassifier.isGpuZone(it.type) }
        val readable = gpuZones.mapNotNull { zone -> zone.rawMilliCelsius?.let(ThermalZoneClassifier::celsius) }
        if (readable.isEmpty()) {
            return Capability.unsupported(
                REASON_NOT_SUPPORTED,
                "no GPU-type thermal zone with a plausible reading under ${MetricPaths.THERMAL_CLASS_ROOT}",
            )
        }
        return Capability(
            supported = true,
            detail = "read GPU-type zones: ${gpuZones.joinToString { "${it.type} (${it.path})" }}",
        )
    }

    private fun batteryPropertyCapability(property: Int, name: String): Capability {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return Capability.unsupported(REASON_NOT_AVAILABLE, "BatteryManager service unavailable")
        val value = try {
            manager.getLongProperty(property)
        } catch (error: Exception) {
            null
        }
        return if (value == null || value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) {
            Capability.unsupported(
                REASON_NOT_AVAILABLE,
                "BatteryManager.$name returned the unsupported sentinel on this device",
            )
        } else {
            Capability(supported = true, detail = "BatteryManager.$name = $value")
        }
    }

    private fun batteryExtraCapability(extra: String, name: String): Capability {
        val intent = stickyBatteryIntent()
            ?: return Capability.unsupported(REASON_NOT_AVAILABLE, "ACTION_BATTERY_CHANGED sticky broadcast unavailable")
        val present = intent.hasExtra(extra)
        return if (present) {
            Capability(supported = true, detail = "ACTION_BATTERY_CHANGED carries $name")
        } else {
            Capability.unsupported(REASON_NOT_AVAILABLE, "ACTION_BATTERY_CHANGED does not carry $name on this device")
        }
    }

    private fun memoryDetailCapability(): Capability {
        val result = NodeProbe.read(MetricPaths.PROC_MEMINFO, reader)
        if (result is NodeReadResult.Failure) {
            return Capability.partial(
                "ActivityManager.MemoryInfo still works, but ${result.detail} (${result.reasonKey})",
            )
        }
        val detail = result as NodeReadResult.Ok
        val parsed = MemInfoParser.parse(detail.text)
        val missing = buildList {
            if (parsed.cachedBytes == null) add("Cached")
            if (parsed.freeBytes == null) add("MemFree")
            if (parsed.swapTotalBytes == null) add("SwapTotal")
        }
        return if (missing.isEmpty()) {
            Capability(supported = true, detail = "read ${detail.path}: MemTotal/MemAvailable/Cached/MemFree/SwapTotal present")
        } else {
            Capability.partial("read ${detail.path} but these fields are absent: ${missing.joinToString()}")
        }
    }

    private fun storageCapability(): Capability {
        val systemPath = Environment.getDataDirectory().absolutePath
        val systemTotal = statFsTotal(systemPath)
        val externalPath = primaryExternalPath()
        val externalTotal = externalPath?.let(::statFsTotal)
        return when {
            systemTotal != null && systemTotal > 0L && externalTotal != null && externalTotal > 0L ->
                Capability(supported = true, detail = "StatFs($systemPath) and StatFs($externalPath) both report a capacity")

            systemTotal != null && systemTotal > 0L ->
                Capability.partial("StatFs($systemPath) works; external volume unreadable (path=${externalPath ?: "unmounted"})")

            else -> Capability.unsupported(REASON_NOT_AVAILABLE, "StatFs($systemPath) reported no capacity")
        }
    }

    private fun networkTrafficCapability(): Capability {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0L && tx >= 0L) {
            Capability(
                supported = true,
                detail = "TrafficStats.getTotalRxBytes()/getTotalTxBytes() supported (rx=$rx, tx=$tx); per-interface counters are restricted since API 31",
            )
        } else {
            Capability.unsupported(
                REASON_NOT_SUPPORTED,
                "TrafficStats.getTotalRxBytes()=$rx / getTotalTxBytes()=$tx returned UNSUPPORTED (-1)",
            )
        }
    }

    private fun wifiSsidCapability(): Capability {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) {
            Capability(
                supported = true,
                detail = "ACCESS_FINE_LOCATION granted; WifiManager.connectionInfo may return the SSID (redaction still applies on Android 10+)",
            )
        } else {
            Capability.unsupported(
                REASON_LOCATION_PERMISSION,
                "WifiManager SSID reads require ACCESS_FINE_LOCATION; the app never requests it itself",
            )
        }
    }

    private fun cpuModel(): String {
        val cpuInfo = reader.readText(MetricPaths.PROC_CPUINFO)
        val fromProc = cpuInfo?.let(CpuInfoParser::model)
        if (fromProc != null) return fromProc
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        return soc?.takeIf { it.isNotBlank() } ?: Build.HARDWARE ?: ""
    }

    private fun devfreqNode(fileName: String): List<String> {
        val entries = reader.listNames(MetricPaths.DEV_FREQ_ROOT)
        return entries.filter { it.contains("gpu", ignoreCase = true) || it.contains("kgsl", ignoreCase = true) }
            .sorted()
            .map { "${MetricPaths.DEV_FREQ_ROOT}/$it/$fileName" }
    }

    private fun statFsTotal(path: String): Long? = try {
        val statistics = StatFs(path)
        val total = statistics.blockCountLong * statistics.blockSizeLong
        total.takeIf { it > 0L }
    } catch (error: Exception) {
        null
    }

    private fun primaryExternalPath(): String? {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volume = storageManager?.let { manager ->
            runCatching { manager.storageVolumes.firstOrNull { it.isPrimary } ?: manager.storageVolumes.firstOrNull() }
                .getOrNull()
        }
        if (volume != null) {
            val state = runCatching { volume.state }.getOrNull()
            if (state == Environment.MEDIA_MOUNTED && Build.VERSION.SDK_INT >= 30) {
                val directory = runCatching { volume.directory?.absolutePath }.getOrNull()
                if (!directory.isNullOrBlank()) return directory
            }
        }
        @Suppress("DEPRECATION")
        val fallback = Environment.getExternalStorageDirectory()?.absolutePath
        return fallback?.takeIf { Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED }
    }

    private fun stickyBatteryIntent(): Intent? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    } catch (error: Exception) {
        null
    }

    private companion object {
        const val REASON_NOT_SUPPORTED = "capability_not_supported_by_android"
        const val REASON_NOT_AVAILABLE = "capability_not_available_on_device"
        const val REASON_NODE_MISSING = NodeProbe.REASON_NODE_MISSING
        const val REASON_SELINUX_DENIED = NodeProbe.REASON_SELINUX_DENIED
        const val REASON_LOCATION_PERMISSION = "capability_requires_location_permission"
        const val REASON_PERMISSION_MISSING = "capability_permission_missing"
    }
}
