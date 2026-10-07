package com.glacierglimmer.endfieldchargeplus.core.metrics

/** Descriptions of the Android data sources, shared by the library and variable picker. */
object AndroidVariableDescriptions {
    fun adapt(value: VariableDescriptor): VariableDescriptor {
        val (zh, en) = source(value)
        return value.copy(
            descriptionZh = "${value.labelZh}。$zh",
            descriptionEn = "${value.labelEn}. $en",
            androidNote = zh,
            androidNoteEn = en,
        )
    }

    private fun source(value: VariableDescriptor): Pair<String, String> {
        if (!value.isSupportedOnAndroid) return "Android 未公开此指标的读取接口；本应用不采集，显示为不可用。" to
                "Android exposes no supported reading API for this metric; the app reports it as unavailable."
        return when (value.name.substringBefore('.')) {
            "disk" -> "通过 Android StatFs 读取分区容量；可用空间不含系统保留块。" to
                "Partition capacity from Android StatFs; available space excludes reserved blocks."
            "battery" -> "通过 Android 电池广播或 BatteryManager 读取；设备未提供时显示为不可用。" to
                "Read from Android battery broadcasts or BatteryManager; unavailable when the device does not provide it."
            "cpu" -> "读取 /proc/stat、/proc/cpuinfo 或 cpufreq 内核节点；系统限制访问或尚无有效采样时显示为不可用。" to
                "Read from /proc/stat, /proc/cpuinfo or cpufreq nodes; unavailable when access is restricted or no valid sample exists."
            "gpu" -> "读取设备开放的 GPU 内核节点；没有公开数据或权限时显示为不可用。" to
                "Read from GPU nodes exposed by the device; unavailable without readable data or permission."
            "memory" -> "读取 Android 内存接口或 /proc/meminfo；只发布设备实际提供的数据。" to
                "Read from Android memory APIs or /proc/meminfo; only readings provided by the device are published."
            "network" -> "通过 TrafficStats 或 Android 网络接口读取；可用范围取决于系统版本和权限。" to
                "Read through TrafficStats or Android networking APIs; availability depends on OS version and permissions."
            "probe", "ping" -> "对配置的目标执行 ICMP、TCP 或 UDP 网络探测；请求失败时显示未知状态。" to
                "ICMP, TCP or UDP probe to the configured target; a failed request reports unknown status."
            "device" -> "读取 Android Build 及设备系统接口。" to "Read from Android Build and device system APIs."
            "custom", "http" -> "来自已配置的 HTTP/JSON 数据源；变量含义由字段映射决定。" to
                "From the configured HTTP/JSON source; field mappings define the metric."
            "deepseek" -> "根据 DeepSeek 数据源配置读取或计算；余额请求需要用户提供 API Key。" to
                "Read or calculated from the DeepSeek source configuration; balance requests need the user's API key."
            "time" -> "根据本设备时间和方案设置计算。" to "Calculated from the device clock and profile settings."
            else -> "由 Android 系统接口提供；数据不可读取时显示为不可用。" to
                "Provided by Android system APIs; unreadable data is reported as unavailable."
        }
    }
}
