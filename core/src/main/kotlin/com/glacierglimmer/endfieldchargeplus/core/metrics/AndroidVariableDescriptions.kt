package com.glacierglimmer.endfieldchargeplus.core.metrics

/** Descriptions of the Android data sources, shared by the library and variable picker. */
object AndroidVariableDescriptions {
    fun adapt(value: VariableDescriptor): VariableDescriptor {
        val (zh, en) = source(value)
        val labels = when (value.name) {
            "cpu.cores" -> "CPU 可用逻辑核心数" to "Available logical CPU cores"
            "disk.data.total_bytes" -> "主外部存储总容量" to "Primary external storage total"
            "disk.data.used_bytes" -> "主外部存储已用空间" to "Primary external storage used"
            "disk.data.available_bytes" -> "主外部存储可用空间" to "Primary external storage available"
            "disk.data.usage" -> "主外部存储使用率" to "Primary external storage usage"
            "disk.data.free_percent" -> "主外部存储空闲率" to "Primary external storage free percent"
            else -> value.labelZh to value.labelEn
        }
        return value.copy(
            labelZh = labels.first,
            labelEn = labels.second,
            descriptionZh = "${labels.first}。$zh",
            descriptionEn = "${labels.second}. $en",
            androidNote = zh,
            androidNoteEn = en,
        )
    }

    private fun source(value: VariableDescriptor): Pair<String, String> {
        if (!value.isSupportedOnAndroid) return "Android 未公开此指标的读取接口；本应用不采集，显示为不可用。" to
                "Android exposes no supported reading API for this metric; the app reports it as unavailable."
        when (value.name) {
            "cpu.cores" -> return "通过 Runtime.availableProcessors 读取应用可用逻辑核心数，可能受省电或进程限制。" to
                "Logical CPUs available to the app from Runtime.availableProcessors; power or process limits can affect the count."
            "cpu.abi" -> return "通过 Build.SUPPORTED_ABIS 读取系统首选 ABI。" to "Preferred system ABI from Build.SUPPORTED_ABIS."
            "gpu.model" -> return "读取厂商 KGSL GPU 型号节点；可用性取决于机型及 Root 授权。" to
                "GPU model from the vendor KGSL node; availability depends on the device and Root authorization."
            "battery.remaining_mwh" -> return "优先读取 BatteryManager 能量计数，否则根据真实电荷计数和电压估算剩余能量。" to
                "BatteryManager energy counter, falling back to an estimate from the measured charge counter and voltage."
            "battery.full_mwh" -> return "按当前剩余能量及电量百分比估算满充能量，不代表额定设计容量。" to
                "Full-charge energy estimated from remaining energy and charge percentage, not rated design capacity."
        }
        return when (value.name.substringBefore('.')) {
            "disk" -> "通过 Android StatFs 读取分区容量；可用空间不含系统保留块。" to
                "Partition capacity from Android StatFs; available space excludes reserved blocks."
            "battery" -> "通过 Android 电池广播或 BatteryManager 读取；设备未提供时显示为不可用。" to
                "Read from Android battery broadcasts or BatteryManager; unavailable when the device does not provide it."
            "cpu" -> "读取 /proc/stat、/proc/cpuinfo、cpufreq 或 CPU 热区；可通过授权 Root 补充读取受限节点，无有效采样时显示为不可用。" to
                "Read from /proc/stat, /proc/cpuinfo, cpufreq or CPU thermal zones; authorized Root can read restricted nodes. Unavailable without a valid sample."
            "gpu" -> "读取 KGSL、Mali、devfreq 或 GPU 热区；可通过授权 Root 补充读取，机型未提供节点时显示为不可用。" to
                "Read from KGSL, Mali, devfreq or GPU thermal zones; authorized Root can supplement access. Unavailable when the device provides no node."
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
