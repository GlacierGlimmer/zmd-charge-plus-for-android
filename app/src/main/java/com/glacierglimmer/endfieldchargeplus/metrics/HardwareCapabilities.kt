package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings

/**
 * What this specific device and Android version actually allow an unprivileged app to read.
 *
 * The Android edition never invents CPU/GPU numbers: this report is what the settings UI, the
 * diagnostics page and the README use to state honestly which metrics are supported.
 */
data class HardwareCapabilities(
    val cpuTotalUsage: Capability,
    val cpuPerCoreUsage: Capability,
    val cpuFrequency: Capability,
    val cpuTemperature: Capability,
    val gpuUsage: Capability,
    val gpuFrequency: Capability,
    val gpuTemperature: Capability,
    val gpuMemory: Capability,
    val batteryCurrent: Capability,
    val batteryTemperature: Capability,
    val batteryVoltage: Capability,
    val memoryDetail: Capability,
    val storage: Capability,
    val networkTraffic: Capability,
    val wifiSsid: Capability,
    val probeIcmp: Capability,
    val probeTcp: Capability,
    val probeUdp: Capability,
    val cpuModel: String = "",
    val cpuCoreCount: Int = 0,
    val deviceModel: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val scannedAtMs: Long = 0L,
) {
    /**
     * Human readable verdict list used by the diagnostics report. Labels follow the active UI
     * language (the diagnostics text is shared, so it must not leak Chinese into an English session).
     */
    fun summarise(): List<Pair<String, Capability>> = listOf(
        Strings.t("CPU 总占用", "CPU total usage") to cpuTotalUsage,
        Strings.t("CPU 每核心", "CPU per core") to cpuPerCoreUsage,
        Strings.t("CPU 频率", "CPU frequency") to cpuFrequency,
        Strings.t("CPU 温度", "CPU temperature") to cpuTemperature,
        Strings.t("GPU 占用", "GPU usage") to gpuUsage,
        Strings.t("GPU 频率", "GPU frequency") to gpuFrequency,
        Strings.t("GPU 温度", "GPU temperature") to gpuTemperature,
        Strings.t("GPU 显存", "GPU memory") to gpuMemory,
        Strings.t("电池电流", "Battery current") to batteryCurrent,
        Strings.t("电池温度", "Battery temperature") to batteryTemperature,
        Strings.t("电池电压", "Battery voltage") to batteryVoltage,
        Strings.t("内存明细", "Memory detail") to memoryDetail,
        Strings.t("存储", "Storage") to storage,
        Strings.t("网络速率", "Network rates") to networkTraffic,
        Strings.t("Wi-Fi SSID", "Wi-Fi SSID") to wifiSsid,
        Strings.t("探测 ICMP", "Probe ICMP") to probeIcmp,
        Strings.t("探测 TCP", "Probe TCP") to probeTcp,
        Strings.t("探测 UDP", "Probe UDP") to probeUdp,
    )

    companion object {
        val Unknown = Capability(
            supported = false,
            reasonKey = "capability_not_scanned",
            detail = "Hardware capabilities have not been scanned yet.",
        )

        /**
         * The full report used before the first real scan: every capability explicitly says
         * "not scanned" instead of pretending to be supported.
         */
        fun notScanned(): HardwareCapabilities {
            val notScanned = Unknown
            return HardwareCapabilities(
                cpuTotalUsage = notScanned,
                cpuPerCoreUsage = notScanned,
                cpuFrequency = notScanned,
                cpuTemperature = notScanned,
                gpuUsage = notScanned,
                gpuFrequency = notScanned,
                gpuTemperature = notScanned,
                gpuMemory = notScanned,
                batteryCurrent = notScanned,
                batteryTemperature = notScanned,
                batteryVoltage = notScanned,
                memoryDetail = notScanned,
                storage = notScanned,
                networkTraffic = notScanned,
                wifiSsid = notScanned,
                probeIcmp = notScanned,
                probeTcp = notScanned,
                probeUdp = notScanned,
            )
        }
    }
}

/**
 * One capability verdict.
 *
 * @param supported true only when the metric can really be produced on this device.
 * @param reasonKey stable localization key explaining why it is unsupported.
 * @param detail extra diagnostic text (for example the sysfs path that was probed).
 */
data class Capability(
    val supported: Boolean,
    val reasonKey: String = "",
    val detail: String = "",
) {
    companion object {
        val Supported = Capability(supported = true)
        fun unsupported(reasonKey: String, detail: String = "") = Capability(false, reasonKey, detail)
        fun partial(detail: String) = Capability(false, "capability_partial", detail)
    }
}
