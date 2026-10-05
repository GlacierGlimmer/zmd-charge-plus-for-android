package com.glacierglimmer.endfieldchargeplus.ui.state

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.metrics.Capability
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities

/**
 * Presentation of the hardware capability report for the settings UI.
 *
 * The **report text** is owned by the canonical `diagnostics/DiagnosticsReport.kt`; this object only
 * supplies the bilingual labels the Advanced and About pages render, so there is exactly one
 * implementation of each behaviour (the engine's own `summarise()` keeps its Chinese labels and is
 * never used for UI text).
 */
object CapabilityPresentation {

    /** Capability rows in a stable order, with labels in the current language. */
    fun rows(capabilities: HardwareCapabilities): List<Pair<String, Capability>> = listOf(
        label("CPU 总占用", "CPU total usage") to capabilities.cpuTotalUsage,
        label("CPU 每核心", "CPU per core") to capabilities.cpuPerCoreUsage,
        label("CPU 频率", "CPU frequency") to capabilities.cpuFrequency,
        label("CPU 温度", "CPU temperature") to capabilities.cpuTemperature,
        label("GPU 占用", "GPU usage") to capabilities.gpuUsage,
        label("GPU 频率", "GPU frequency") to capabilities.gpuFrequency,
        label("GPU 温度", "GPU temperature") to capabilities.gpuTemperature,
        label("GPU 显存", "GPU memory") to capabilities.gpuMemory,
        label("电池电流", "Battery current") to capabilities.batteryCurrent,
        label("电池温度", "Battery temperature") to capabilities.batteryTemperature,
        label("电池电压", "Battery voltage") to capabilities.batteryVoltage,
        label("内存明细", "Memory detail") to capabilities.memoryDetail,
        label("存储", "Storage") to capabilities.storage,
        label("网络速率", "Network traffic") to capabilities.networkTraffic,
        label("Wi-Fi 名称", "Wi-Fi SSID") to capabilities.wifiSsid,
        label("探测 ICMP", "Probe ICMP") to capabilities.probeIcmp,
        label("探测 TCP", "Probe TCP") to capabilities.probeTcp,
        label("探测 UDP", "Probe UDP") to capabilities.probeUdp,
    )

    /** "N of M metrics supported", used as the honest capability summary. */
    fun supportSummary(capabilities: HardwareCapabilities): String {
        val rows = rows(capabilities)
        val supported = rows.count { it.second.supported }
        return Strings.t(
            "支持 $supported / ${rows.size} 项指标",
            "$supported of ${rows.size} metrics supported",
        )
    }

    private fun label(zh: String, en: String): String = Strings.t(zh, en)
}
