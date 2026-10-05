package com.glacierglimmer.endfieldchargeplus.core.metrics

/**
 * The data type of a variable, as shown by the settings "变量库" page.
 *
 * The desktop catalogs store a Chinese type name (`数值`, `整数`, `文本`, `布尔`, `动态`); Android
 * maps those to [NUMBER] (`数值`/`整数`/`动态`), [TEXT] and [BOOLEAN], and adds two Android-specific
 * states:
 *
 *  * [PROGRESS] — a percentage-like reading that can drive the HUD progress ring (`unit == "%"`, or
 *    a key ending in `.progress` / `.usage` / `.percent` / `.loss_percent` / `.active_percent` / ...).
 *  * [UNAVAILABLE] — the Android app has no honest way to collect this metric. ECP's cross-platform
 *    rule is "unmeasurable ⇒ absent, never 0"; the descriptor therefore carries an [androidNote]
 *    that explains the platform limitation, the variable stays out of the capability catalog and
 *    `--` remains the only rendering for it.
 */
enum class VariableType {
    /** Numeric reading (integer or floating point). */
    NUMBER,

    /** Textual reading. */
    TEXT,

    /** Boolean reading. */
    BOOLEAN,

    /** Percentage-style reading that can drive the progress ring. */
    PROGRESS,

    /** Android cannot measure this metric; see [VariableDescriptor.androidNote]. */
    UNAVAILABLE,
}

/**
 * One entry of the ECP variable library.
 *
 * [name] is the variable key (`cpu.usage`), [categoryKey] is the ECP category (`CPU`, `电池`, ... —
 * the desktop spellings, translated for display by `BuiltInProfileLocalization.categoryName`),
 * [descriptionZh]/[descriptionEn] are the ECP Chinese description and its ECP English wording, and
 * [commonFormats] lists the catalog's recommended format tokens for `{variable|format}`.
 *
 * [labelZh]/[labelEn]/[recommendedUseZh]/[recommendedUseEn] are additive (defaulted) fields so the
 * settings page can show the same short name and usage hint the desktop variable library shows;
 * they never break positional construction of the seven documented fields.
 */
data class VariableDescriptor(
    /** Variable key, for example `cpu.usage`. */
    val name: String,

    /** ECP category key (`CPU`, `GPU`, `内存`, `磁盘`, `电池`, `网络`, ...). */
    val categoryKey: String,

    /** Android-adjusted [VariableType]. */
    val type: VariableType,

    /** Unit as printed by the desktop catalog (`%`, `Byte`, `µAh`, `GHz`, ...); empty when unitless. */
    val unit: String,

    /** The desktop catalog's Chinese description. */
    val descriptionZh: String,

    /** The ECP English wording (ported from `VariableLocalization.BuildDescription`). */
    val descriptionEn: String,

    /** Recommended `{variable|format}` tokens, in catalog order; empty when no format is needed. */
    val commonFormats: List<String>,

    /**
     * Honest Android limitation / data-source note. Non-empty for every [VariableType.UNAVAILABLE]
     * entry and for available metrics whose Android data source differs from the desktop one.
     */
    val androidNote: String = "",

    /** Short Chinese name from the desktop catalog (`CPU 使用率`). */
    val labelZh: String = "",

    /** Short English name derived from the key with the desktop token map (`CPU Usage`). */
    val labelEn: String = "",

    /** Chinese "recommended use" hint (`右侧状态 / 圆环`). */
    val recommendedUseZh: String = "",

    /** English "recommended use" hint (`Right status / ring`). */
    val recommendedUseEn: String = "",
) {
    /** True when the Android app can really read this variable. */
    val isSupportedOnAndroid: Boolean get() = type != VariableType.UNAVAILABLE

    /** The ECP template token, for example `{cpu.usage}`. */
    val templateToken: String get() = "{$name}"
}

/**
 * The ECP variable library used by the settings "变量库" page, by the expression engine's
 * documentation and by the collector request planner.
 *
 * Sources, in order:
 *
 *  1. The real ECP catalogs — the 429-key Windows catalog, which has the identical key set to the
 *     Linux catalog, plus the per-platform definitions the Linux/macOS catalogs append at run time
 *     (`memory.swap_*`, `memory.free_bytes`, `disk.system.available_bytes`,
 *     `battery.time_to_full_seconds`, `display.virtual_*_points`, ...).
 *  2. Dynamic ECP families: `disk.<字母>.*` (Windows drive letters), `disk.mount_<hash>.*` (Linux
 *     per-volume keys) and `custom.<source>.<field>` (HTTP/JSON sources).
 *  3. The Android cross-platform contract names declared in [Variables] that no desktop catalog
 *     spells the same way (`device.*`, `memory.low`, `network.connected`, `disk.data.*`,
 *     `battery.charge_counter`, `time.day_progress`, ...); each one names the desktop spelling in
 *     its description and/or androidNote.
 *
 * Nothing is invented: a key is only present when ECP publishes it on some platform or when
 * [Variables] declares it for Android. [Variables.CPU_PER_CORE_PREFIX] (`cpu.core`) is documented
 * but deliberately not enumerated — the desktop catalogs publish only `cpu.core.temperature_avg`
 * and `cpu.core.voltage`, and per-core usage keys would be an invention.
 *
 * Android-impossible metrics (CPU per-core sensors, CPU/GPU temperature, GPU load and VRAM, disk
 * I/O, Windows security/devices, ...) are present with [VariableType.UNAVAILABLE] and an honest
 * [VariableDescriptor.androidNote]; they are never reported as `0`.
 */
object VariableRegistry {

    private val RAW_TABLE: String = """
        cpu.usage|CPU|PROGRESS|%|CPU 使用率|Usage|当前整机 CPU 总使用率。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.user_usage|CPU|PROGRESS|%|CPU 用户态使用率|User Usage|CPU 时间中用于用户态代码的比例。|Current User Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.kernel_usage|CPU|PROGRESS|%|CPU 内核态使用率|Kernel Usage|CPU 时间中用于内核态且不含空闲时间的比例。|Current Kernel Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.idle_percent|CPU|PROGRESS|%|CPU 空闲率|Idle Percent|CPU 当前空闲时间比例。|Current Idle Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.frequency_ghz|CPU|NUMBER|GHz|CPU 当前频率|Frequency GHz|Windows 性能数据报告的当前实时有效频率。|Current Frequency GHz, reported in GHz.|0.00|左侧主值|Primary value|多数机型可读 /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq，但 Android 10+ 部分机型禁读；先探测再发布。
        cpu.frequency_mhz|CPU|NUMBER|MHz|CPU 当前频率（MHz）|Frequency MHz|当前实时有效频率，单位 MHz。|Current Frequency MHz, reported in MHz.|0|左侧主值|Primary value|多数机型可读 /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq，但 Android 10+ 部分机型禁读；先探测再发布。
        cpu.max_frequency_ghz|CPU|NUMBER|GHz|CPU 最大频率|Max Frequency GHz|Win32_Processor 报告的最大时钟频率。|Current Max Frequency GHz, reported in GHz.|0.00|左侧次值|Secondary value|读取 /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq，通常可读；不可读时省略。
        cpu.max_frequency_mhz|CPU|NUMBER|MHz|CPU 最大频率（MHz）|Max Frequency MHz|最大时钟频率，单位 MHz。|Current Max Frequency MHz, reported in MHz.|0|左侧次值|Secondary value|读取 /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq，通常可读；不可读时省略。
        cpu.frequency_percent|CPU|PROGRESS|%|CPU 频率比例|Frequency Percent|当前有效频率相对最大频率的百分比，允许睿频时超过 100%。|Current Frequency Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|多数机型可读 /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq，但 Android 10+ 部分机型禁读；先探测再发布。
        cpu.name|CPU|TEXT||CPU 名称|Name|处理器完整型号名称。|Name reported for Name.||标题 / 左侧信息|Title / Left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.manufacturer|CPU|TEXT||CPU 制造商|Manufacturer|处理器制造商。|Current Manufacturer.||标题 / 左侧信息|Title / Left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.architecture|CPU|TEXT||CPU 架构|Architecture|处理器架构，如 x64 / ARM64。|Current Architecture.||标题 / 左侧信息|Title / Left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.physical_cores|CPU|NUMBER|核|CPU 物理核心数|Physical Cores|所有处理器插槽的物理核心总数。|Current Physical Cores, reported in cores.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.logical_processors|CPU|NUMBER|线程|CPU 逻辑处理器数|Logical Processors|系统可见的逻辑处理器总数。|Current Logical Processors, reported in threads.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.socket_count|CPU|NUMBER|个|CPU 插槽数|Socket Count|系统中处理器插槽数量。|Current Socket Count.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.virtualization_enabled|CPU|BOOLEAN||CPU 固件虚拟化|Virtualization Enabled|固件虚拟化功能是否启用；依赖 WMI 支持。|Whether virtualization enabled is active or true.||标题 / 条件|Title / Condition|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.l2_cache_kb|CPU|NUMBER|KB|CPU L2 缓存|L2 Cache KB|处理器报告的 L2 缓存容量。|Current L2 Cache KB, reported in KB.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.l3_cache_kb|CPU|NUMBER|KB|CPU L3 缓存|L3 Cache KB|处理器报告的 L3 缓存容量。|Current L3 Cache KB, reported in KB.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.usage_avg_1m|CPU|PROGRESS|%|CPU 1 分钟平均使用率|Usage Avg 1m|该变量启用后滚动统计最近 1 分钟 CPU 使用率。|Current Usage Avg 1m, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.usage_avg_5m|CPU|PROGRESS|%|CPU 5 分钟平均使用率|Usage Avg 5m|该变量启用后滚动统计最近 5 分钟 CPU 使用率。|Current Usage Avg 5m, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.usage_avg_15m|CPU|UNAVAILABLE|%|CPU 15 分钟平均使用率|Usage Avg 15m|该变量启用后滚动统计最近 15 分钟 CPU 使用率。|Current Usage Avg 15m, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.usage_max|CPU|PROGRESS|%|CPU 近期最高使用率|Usage Max|最近最多 15 分钟采样窗口内观察到的 CPU 最高使用率。|Current Usage Max, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.temperature_max|CPU|UNAVAILABLE|°C|CPU 最高温度|Temperature Max|LibreHardwareMonitor 读取的 CPU 当前最高温度；硬件/驱动不支持时不可用。|Current Temperature Max, reported in °C.|0.0|右侧状态 / 圆环|Right status / Ring|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.power_max|CPU|UNAVAILABLE|W|CPU 功耗|Power Max|LibreHardwareMonitor 当前 CPU 功率传感器的最高有效值。|Current Power Max, reported in W.|0.0|左侧信息|Left info|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.core.temperature_avg|CPU|UNAVAILABLE|°C|CPU 核心平均温度|Core Temperature Avg|可用核心温度传感器的实时平均值。|Current Core Temperature Avg, reported in °C.|0.0|左侧信息|Left info|ECP 的逐核温度/电压来自 Windows LibreHardwareMonitor，Android 无对应公开 API，因此该键保持不可用；不虚构逐核数值。
        cpu.core.voltage|CPU|UNAVAILABLE|V|CPU 核心电压|Core Voltage|硬件监控传感器报告的 CPU 核心/最高有效电压。|Current Core Voltage, reported in V.|0.000|左侧信息|Left info|ECP 的逐核温度/电压来自 Windows LibreHardwareMonitor，Android 无对应公开 API，因此该键保持不可用；不虚构逐核数值。
        cpu.bus_speed|CPU|UNAVAILABLE|MHz|CPU 总线/BCLK|Bus Speed|硬件监控传感器报告的 Bus/BCLK 频率。|Current Bus Speed, reported in MHz.|0.0|左侧信息|Left info|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.instructions_per_second|CPU|UNAVAILABLE|instr/s|CPU 每秒退休指令|Instructions Per Second|Windows Processor Information 性能计数器 InstructionsRetiredPersec；仅系统提供该计数器时可用。|Current Instructions Per Second, reported in instr/s.|auto:1|左侧信息|Left info|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.context_switches|CPU|NUMBER|次/s|上下文切换速率|Context Switches|Windows System 性能计数器 Context Switches/sec。|Current Context Switches, reported in /s.|0|左侧信息|Left info|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.interrupts|CPU|NUMBER|次/s|硬件中断速率|Interrupts|Windows Processor Information 性能计数器 Interrupts/sec。|Current Interrupts, reported in /s.|0|左侧信息|Left info|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.dpc_time|CPU|UNAVAILABLE|%|DPC 时间比例|DPC Time|Windows Processor Information 的 % DPC Time。|Current DPC Time, reported in %.|0.0|右侧状态 / 圆环|Right status / Ring|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.system_calls|CPU|UNAVAILABLE|次/s|系统调用速率|System Calls|Windows System 性能计数器 System Calls/sec。|Current System Calls, reported in /s.|0|左侧信息|Left info|Windows 性能计数器指标，Android 无对应公开 API；保持不可用而不是伪造 0。
        cpu.abi|CPU|TEXT||CPU ABI|CPU ABI|Android 首选 ABI（Build.SUPPORTED_ABIS）；桌面目录中的同义键是 cpu.architecture。|Preferred Android ABI (Build.SUPPORTED_ABIS).||标题 / 左侧信息|Title / left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.cores|CPU|NUMBER|核|CPU 物理核心数|CPU Cores|物理核心数；桌面目录中的同义键是 cpu.physical_cores。|Physical core count; the desktop catalog spells this cpu.physical_cores.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.model|CPU|TEXT||CPU 型号|CPU Model|Android SoC 型号（Build.SOC_MODEL，API 31+）；桌面同义键是 cpu.name。|Android SoC model (Build.SOC_MODEL, API 31+).||标题 / 左侧信息|Title / left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.temperature_c|CPU|UNAVAILABLE|°C|CPU 温度|CPU Temperature|CPU 温度；Android 无公开 API（详见 androidNote）。|CPU temperature; Android exposes no public API.|0|左侧信息|Left info|Android 没有公开的 CPU 温度 API；/sys/class/thermal 与 hwmon 受 SELinux 限制且因机型而异。仅在真实读取成功时发布，否则省略该键而不是填 0。
        gpu.usage|GPU|UNAVAILABLE|%|GPU 总使用率|Usage|当前所选 GPU 的实时利用率，取最忙 GPU 引擎。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.usage_3d|GPU|UNAVAILABLE|%|GPU 3D 使用率|Usage 3d|当前所选 GPU 的 3D 引擎最高实时利用率。|Current Usage 3d, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.usage_compute|GPU|UNAVAILABLE|%|GPU Compute 使用率|Usage Compute|当前所选 GPU 的计算引擎最高实时利用率。|Current Usage Compute, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.usage_copy|GPU|UNAVAILABLE|%|GPU Copy 使用率|Usage Copy|当前所选 GPU 的复制引擎最高实时利用率。|Current Usage Copy, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.usage_video_decode|GPU|UNAVAILABLE|%|GPU 视频解码使用率|Usage Video Decode|当前所选 GPU 的视频解码引擎最高实时利用率。|Current Usage Video Decode, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.usage_video_encode|GPU|UNAVAILABLE|%|GPU 视频编码使用率|Usage Video Encode|当前所选 GPU 的视频编码引擎最高实时利用率。|Current Usage Video Encode, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.name|GPU|TEXT||GPU 名称|Name|当前方案选择的显示适配器名称。|Name reported for Name.||标题 / 左侧信息|Title / Left info|
        gpu.adapter_id|GPU|UNAVAILABLE||GPU 设备 ID|Adapter ID|当前所选 GPU 的内部稳定标识。|Current Adapter ID.||调试 / 自定义|Debug / Custom|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.physical_index|GPU|UNAVAILABLE||GPU 物理索引|Physical Index|Windows GPU 性能计数器对应的物理 GPU 索引。|Current Physical Index.|0|调试 / 左侧信息|Debug / Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.count|GPU|NUMBER|个|GPU 数量|Count|当前系统检测到的物理 GPU 数量。|Current Count.|0|左侧信息|Left info|
        gpu.vram_bytes|GPU|UNAVAILABLE|Byte|GPU 专用显存总量|VRAM Bytes|当前所选 GPU 的专用显存总容量。|Current VRAM Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.dedicated_total_bytes|GPU|UNAVAILABLE|Byte|专用显存总量|Dedicated Total Bytes|当前所选 GPU 的专用显存容量。|Current Dedicated Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.dedicated_used_bytes|GPU|UNAVAILABLE|Byte|已用专用显存|Dedicated Used Bytes|当前所选 GPU 的专用显存实时使用量。|Current Dedicated Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.dedicated_usage|GPU|UNAVAILABLE|%|专用显存使用率|Dedicated Usage|已用专用显存占专用显存总量的比例。|Current Dedicated Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.shared_limit_bytes|GPU|UNAVAILABLE|Byte|共享 GPU 内存上限|Shared Limit Bytes|当前 GPU 可使用的共享系统内存上限。|Current Shared Limit Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.shared_used_bytes|GPU|UNAVAILABLE|Byte|已用共享 GPU 内存|Shared Used Bytes|当前 GPU 使用的共享系统内存。|Current Shared Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.shared_usage|GPU|UNAVAILABLE|%|共享 GPU 内存使用率|Shared Usage|已用共享 GPU 内存占共享上限的比例。|Current Shared Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.memory_used_bytes|GPU|UNAVAILABLE|Byte|GPU 当前显存占用|Memory Used Bytes|默认 HUD 使用的专用显存占用。|Current Memory Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.memory_total_bytes|GPU|UNAVAILABLE|Byte|GPU 显存总量|Memory Total Bytes|默认 HUD 使用的专用显存总量。|Current Memory Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.total_memory_used_bytes|GPU|UNAVAILABLE|Byte|GPU 总内存占用|Total Memory Used Bytes|专用显存占用与共享 GPU 内存占用之和。|Current Total Memory Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.total_memory_limit_bytes|GPU|UNAVAILABLE|Byte|GPU 总可用内存上限|Total Memory Limit Bytes|专用显存总量与共享内存上限之和。|Current Total Memory Limit Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.total_memory_usage|GPU|UNAVAILABLE|%|GPU 总内存使用率|Total Memory Usage|GPU 总内存占用占总可用内存上限的比例。|Current Total Memory Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.uses_unified_memory|GPU|UNAVAILABLE||GPU 是否主要使用共享内存|Uses Unified Memory|用于区分典型核显/统一内存设备与独立显卡的启发式标记。|Whether uses unified memory is active or true.||标题 / 条件|Title / Condition|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.temperature|GPU|UNAVAILABLE|°C|GPU 核心温度|Temperature|硬件监控传感器报告的 GPU Core 温度；硬件或驱动不提供时变量不可用。|Current Temperature, reported in °C.|0.0|左侧信息|Left info|Android 无公开 GPU 温度 API；桌面端数值来自 LibreHardwareMonitor / nvidia-smi。
        gpu.hotspot_temperature|GPU|UNAVAILABLE|°C|GPU 热点温度|Hotspot Temperature|硬件监控传感器报告的 GPU Hot Spot/核心热点温度。|Current Hotspot Temperature, reported in °C.|0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 温度 API；桌面端数值来自 LibreHardwareMonitor / nvidia-smi。
        gpu.memory_junction_temperature|GPU|UNAVAILABLE|°C|GPU 显存结温|Memory Junction Temperature|硬件监控传感器报告的显存/Memory Junction 温度。|Current Memory Junction Temperature, reported in °C.|0.0|左侧信息|Left info|Android 无公开 GPU 温度 API；桌面端数值来自 LibreHardwareMonitor / nvidia-smi。
        gpu.power_w|GPU|UNAVAILABLE|W|GPU 当前功率|Power W|LibreHardwareMonitor 实时功率传感器值；仅硬件/驱动提供时可用。|Current Power W, reported in W.|0.0|左侧信息|Left info|Android 无公开 GPU 频率/功耗/电压 API；厂商节点不可移植，保持不可用而不是伪造 0。
        gpu.power_limit_w|GPU|UNAVAILABLE|W|GPU 功率限制|Power Limit W|NVIDIA GPU 通过 nvidia-smi power.limit 读取；其他 GPU 若没有可靠功率限制接口则不提供。|Current Power Limit W, reported in W.|0.0|左侧信息|Left info|Android 无公开 GPU 频率/功耗/电压 API；厂商节点不可移植，保持不可用而不是伪造 0。
        gpu.voltage_v|GPU|UNAVAILABLE|V|GPU 核心电压|Voltage V|硬件监控传感器报告的 GPU 核心电压。|Current Voltage V, reported in V.|0.000|左侧信息|Left info|Android 无公开 GPU 频率/功耗/电压 API；厂商节点不可移植，保持不可用而不是伪造 0。
        gpu.core_clock_mhz|GPU|UNAVAILABLE|MHz|GPU 核心频率|Core Clock MHz|当前 GPU 核心实时频率。|Current Core Clock MHz, reported in MHz.|0|左侧信息|Left info|Android 无公开 GPU 频率/功耗/电压 API；厂商节点不可移植，保持不可用而不是伪造 0。
        gpu.memory_clock_mhz|GPU|UNAVAILABLE|MHz|GPU 显存频率|Memory Clock MHz|当前 GPU 显存实时频率。|Current Memory Clock MHz, reported in MHz.|0|左侧信息|Left info|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.pcie_gen|GPU|UNAVAILABLE|Gen|GPU PCIe 代际|PCIe Gen|nvidia-smi 提供的当前 PCIe Link Generation；NVIDIA 且 nvidia-smi 可用时提供。|Current PCIe Gen, reported in Gen.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.pcie_lanes|GPU|UNAVAILABLE|lane|GPU PCIe 通道数|PCIe Lanes|nvidia-smi 提供的当前 PCIe Link Width。|Current PCIe Lanes, reported in lane.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.driver_version|GPU|UNAVAILABLE||GPU 驱动版本|Driver Version|Win32_VideoController 报告的所选 GPU 驱动版本。|Current Driver Version.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.driver_date|GPU|UNAVAILABLE||GPU 驱动日期|Driver Date|Win32_VideoController 报告的驱动日期。|Current Driver Date.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.bios_version|GPU|UNAVAILABLE||GPU VBIOS 版本|BIOS Version|nvidia-smi 报告的 VBIOS 版本；支持时可用。|Current BIOS Version.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        gpu.nvidia_smi_available|GPU|UNAVAILABLE||nvidia-smi 可用|Nvidia Smi Available|系统是否存在可调用的 nvidia-smi.exe。|Whether nvidia smi available is active or true.||条件|Condition|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        gpu.amd_adrenalin_available|GPU|UNAVAILABLE||AMD Adrenalin 可用|Amd Adrenalin Available|检测 AMD Radeon Software 进程或默认安装路径。|Whether amd adrenalin available is active or true.||条件|Condition|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        gpu.unified_memory|GPU|UNAVAILABLE||统一内存架构|Unified Memory|Apple Metal 的 hasUnifiedMemory；Android 无对应语义。|Whether unified memory is active or true.||标题 / 条件|Title / Condition|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.recommended_working_set_bytes|GPU|UNAVAILABLE|Byte|建议工作集上限|Recommended Working Set Bytes|Apple Metal recommendedMaxWorkingSetSize：是显存预算而不是显存容量。|Current Recommended Working Set Bytes, reported in Byte.|gb:1|左侧次值|Secondary value|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.low_power|GPU|UNAVAILABLE||低功耗 GPU|Low Power|Apple Metal isLowPower。|Whether low power is active or true.||标题 / 条件|Title / Condition|Android 无公开 GPU 频率/功耗/电压 API；厂商节点不可移植，保持不可用而不是伪造 0。
        gpu.removable|GPU|UNAVAILABLE||可移除 GPU|Removable|Apple Metal isRemovable。|Whether removable is active or true.||标题 / 条件|Title / Condition|Android 无公开显存统计 API；Metal/macOS 的 working-set 预算是预算而非显存，不得改标为 VRAM，因此保持不可用。
        gpu.model|GPU|TEXT||GPU 型号|GPU Model|通过 EGL 读取的图形适配器名称；桌面同义键是 gpu.name。|Graphics adapter name read through EGL.||标题 / 左侧信息|Title / left info|通过 EGL（GL_RENDERER / GL_VENDOR）读取可验证的图形适配器身份，不推断显存或负载。
        gpu.temperature_c|GPU|UNAVAILABLE|°C|GPU 温度|GPU Temperature|GPU 温度；Android 无公开 API（详见 androidNote）。|GPU temperature; Android exposes no public API.|0|左侧信息|Left info|Android 无公开 GPU 温度 API；桌面端数值来自 LibreHardwareMonitor / nvidia-smi。
        gpu.frequency_ghz|GPU|UNAVAILABLE|GHz|GPU 频率|GPU Frequency|GPU 频率；Android 无公开 API，厂商节点不可移植。|GPU frequency; no public Android API exists.|0.00|左侧信息|Left info|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        memory.used_bytes|内存|NUMBER|Byte|已用物理内存|Used Bytes|当前已使用的物理内存。|Current Used Bytes, reported in Byte.|gb:1;gb:2;bytes|左侧主值|Primary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.available_bytes|内存|NUMBER|Byte|可用物理内存|Available Bytes|当前可用物理内存。|Current Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.total_bytes|内存|NUMBER|Byte|物理内存总量|Total Bytes|物理内存总容量。|Current Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.usage|内存|PROGRESS|%|内存使用率|Usage|已用物理内存占总物理内存的比例。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.free_percent|内存|PROGRESS|%|内存空闲率|Free Percent|可用物理内存占总物理内存的比例。|Current Free Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.commit_used_bytes|内存|NUMBER|Byte|已提交内存|Commit Used Bytes|系统已提交内存量。|Current Commit Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.commit_available_bytes|内存|NUMBER|Byte|剩余提交额度|Commit Available Bytes|提交限制中尚可使用的额度。|Current Commit Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.commit_limit_bytes|内存|NUMBER|Byte|提交限制|Commit Limit Bytes|Windows 当前提交上限，通常受物理内存与页面文件共同影响。|Current Commit Limit Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.commit_usage|内存|PROGRESS|%|提交使用率|Commit Usage|已提交内存占提交限制的比例。|Current Commit Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.virtual_used_bytes|内存|NUMBER|Byte|已用虚拟内存|Virtual Used Bytes|当前系统虚拟地址空间已使用量。|Current Virtual Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.virtual_available_bytes|内存|NUMBER|Byte|可用虚拟内存|Virtual Available Bytes|当前系统可用虚拟地址空间。|Current Virtual Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.virtual_total_bytes|内存|NUMBER|Byte|虚拟内存总量|Virtual Total Bytes|系统报告的虚拟地址空间总量。|Current Virtual Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.virtual_usage|内存|PROGRESS|%|虚拟内存使用率|Virtual Usage|已用虚拟内存占虚拟内存总量的比例。|Current Virtual Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.cache_bytes|内存|NUMBER|Byte|系统缓存内存|Cache Bytes|Windows 性能计数器报告的系统缓存字节数。|Current Cache Bytes, reported in Byte.|gb:1;mb:0;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.paged_pool_bytes|内存|NUMBER|Byte|分页池|Paged Pool Bytes|Windows 内核分页池内存。|Current Paged Pool Bytes, reported in Byte.|mb:0;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.nonpaged_pool_bytes|内存|NUMBER|Byte|非分页池|Nonpaged Pool Bytes|Windows 内核非分页池内存。|Current Nonpaged Pool Bytes, reported in Byte.|mb:0;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.standby_bytes|内存|NUMBER|Byte|Standby 缓存|Standby Bytes|Windows Memory 性能计数器中 Standby Cache Core/Normal/Reserve 的合计。|Current Standby Bytes, reported in Byte.|auto:1;gb:1|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.modified_bytes|内存|NUMBER|Byte|Modified 页面|Modified Bytes|Windows Modified Page List Bytes。|Current Modified Bytes, reported in Byte.|auto:1;mb:0|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.hardware_reserved_bytes|内存|NUMBER|Byte|硬件保留内存|Hardware Reserved Bytes|物理内存条安装容量与 Windows 可用物理内存总量之差。|Current Hardware Reserved Bytes, reported in Byte.|auto:1;mb:0|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.speed_mhz|内存|NUMBER|MHz|内存工作频率|Speed MHz|Win32_PhysicalMemory 报告的最高 ConfiguredClockSpeed/Speed。|Current Speed MHz, reported in MHz.|0|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.slot_count|内存|NUMBER|个|内存插槽总数|Slot Count|Win32_PhysicalMemoryArray 报告的内存设备插槽总数。|Current Slot Count.|0|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.slot_used|内存|NUMBER|个|已使用内存插槽|Slot Used|当前枚举到的物理内存模块数量。|Current Slot Used, reported in count.|0|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.form_factor|内存|TEXT||内存形态|Form Factor|内存模块 FormFactor，例如 DIMM / SODIMM。|Current Form Factor.||左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.type|内存|TEXT||内存类型|Type|SMBIOS 报告的内存类型，例如 DDR4 / DDR5 / LPDDR5。|Current Type.||左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_total_bytes|内存|NUMBER|Byte|交换空间总量|Swap Total Bytes|交换空间总量。|Current Swap Total Bytes, reported in Byte.|0;gb:1|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_available_bytes|内存|NUMBER|Byte|可用交换空间|Swap Available Bytes|可用交换空间。|Current Swap Available Bytes, reported in Byte.|0;gb:1|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_used_bytes|内存|NUMBER|Byte|已用交换空间|Swap Used Bytes|已用交换空间。|Current Swap Used Bytes, reported in Byte.|0;gb:1|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_usage|内存|PROGRESS|%|交换空间使用率|Swap Usage|已用交换空间占交换空间总量的比例。|Current Swap Usage, reported in %.|0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.active_bytes|内存|NUMBER|Byte|活跃内存|Active Bytes|Mach/Linux 活跃内存。|Current Active Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.inactive_bytes|内存|NUMBER|Byte|非活跃内存|Inactive Bytes|非活跃（可回收）内存。|Current Inactive Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.wired_bytes|内存|NUMBER|Byte|内核占用内存|Wired Bytes|macOS Mach wired 内存（内核不可换出部分）。|Current Wired Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|macOS Mach wired 内存是平台专有概念；Android 不发布内核不可换出内存。
        memory.cached_bytes|内存|NUMBER|Byte|系统缓存内存|Cached Memory|Android 缓存/可回收内存；桌面目录中的同义键是 memory.cache_bytes。|Cached (reclaimable) memory; the desktop catalog spells this memory.cache_bytes.|gb:1;mb:0;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.free_bytes|内存|NUMBER|Byte|空闲物理内存|Free Memory|完全空闲的物理内存（MemFree）；桌面 macOS 版同名。|Completely free physical memory (MemFree).|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.low|内存|BOOLEAN||内存紧张标记|Low Memory|ActivityManager.MemoryInfo.lowMemory：系统报告内存紧张。|ActivityManager.MemoryInfo.lowMemory.||标题 / 条件|Title / condition|优先使用 ActivityManager.MemoryInfo.totalMem/availMem，/proc/meminfo 作为补充。
        disk.system.root|磁盘|TEXT||系统盘盘符|System Root|Windows 系统目录所在驱动器根路径，如 C:\。|Current System Root.||标题 / 左侧信息|Title / Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.label|磁盘|TEXT||系统盘卷标|System Label|系统盘卷标。|Current System Label.||标题 / 左侧信息|Title / Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.filesystem|磁盘|TEXT||系统盘文件系统|System Filesystem|系统盘文件系统，如 NTFS。|Current System Filesystem.||标题 / 左侧信息|Title / Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.used_bytes|磁盘|NUMBER|Byte|系统盘已用空间|System Used Bytes|Windows 系统盘已使用空间。|Current System Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.free_bytes|磁盘|NUMBER|Byte|系统盘可用空间|System Free Bytes|Windows 系统盘可用空间。|Current System Free Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.total_bytes|磁盘|NUMBER|Byte|系统盘总容量|System Total Bytes|Windows 系统盘总容量。|Current System Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.usage|磁盘|PROGRESS|%|系统盘使用率|System Usage|系统盘已用空间占总容量的比例。|Current System Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.free_percent|磁盘|PROGRESS|%|系统盘空闲率|System Free Percent|系统盘可用空间占总容量的比例。|Current System Free Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.read_bps|磁盘|UNAVAILABLE|Byte/s|系统盘读取速度|System Read B/s|Windows 逻辑磁盘性能计数器报告的系统盘读取速度。|Current System Read B/s, reported in Byte/s.|speed|左侧主值|Primary value|Android 无公开块设备 I/O API，/proc/diskstats 通常受限；保持不可用而不伪造 0 B/s。
        disk.system.write_bps|磁盘|UNAVAILABLE|Byte/s|系统盘写入速度|System Write B/s|Windows 逻辑磁盘性能计数器报告的系统盘写入速度。|Current System Write B/s, reported in Byte/s.|speed|左侧次值|Secondary value|Android 无公开块设备 I/O API，/proc/diskstats 通常受限；保持不可用而不伪造 0 B/s。
        disk.system.io_bps|磁盘|UNAVAILABLE|Byte/s|系统盘总 IO 速度|System I/O B/s|系统盘读取速度与写入速度之和。|Current System I/O B/s, reported in Byte/s.|speed|左侧信息|Left info|Android 无公开块设备 I/O API，/proc/diskstats 通常受限；保持不可用而不伪造 0 B/s。
        disk.system.active_percent|磁盘|UNAVAILABLE|%|系统盘活动时间|System Active Percent|系统盘性能计数器报告的磁盘活动时间百分比。|Current System Active Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开块设备 I/O API，/proc/diskstats 通常受限；保持不可用而不伪造 0 B/s。
        disk.system.queue_length|磁盘|UNAVAILABLE|项|系统盘队列长度|System Queue Length|系统盘当前磁盘队列长度。|Current System Queue Length, reported in items.|0.0|左侧信息|Left info|Android 无公开块设备 I/O API，/proc/diskstats 通常受限；保持不可用而不伪造 0 B/s。
        disk.fixed.count|磁盘|NUMBER|个|固定磁盘数量|Fixed Count|当前已就绪的固定磁盘卷数量。|Current Fixed Count.|0|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.fixed.used_bytes|磁盘|NUMBER|Byte|所有固定磁盘已用空间|Fixed Used Bytes|所有已就绪固定磁盘卷的已用空间总和。|Current Fixed Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.fixed.free_bytes|磁盘|NUMBER|Byte|所有固定磁盘可用空间|Fixed Free Bytes|所有已就绪固定磁盘卷的可用空间总和。|Current Fixed Free Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.fixed.total_bytes|磁盘|NUMBER|Byte|所有固定磁盘总容量|Fixed Total Bytes|所有已就绪固定磁盘卷的总容量。|Current Fixed Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.fixed.usage|磁盘|PROGRESS|%|所有固定磁盘总体使用率|Fixed Usage|所有固定磁盘已用空间占总容量的比例。|Current Fixed Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.fixed.list|磁盘|TEXT||固定磁盘列表|Fixed List|已就绪固定磁盘盘符列表。|Current Fixed List.||标题 / 左侧信息|Title / Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.available_bytes|磁盘|NUMBER|Byte|系统卷可用空间|System Available Bytes|系统卷普通用户可用空间（macOS 版定义）。|Current System Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.total_bytes|磁盘|NUMBER|Byte|数据分区总容量|Data Total|应用数据分区的总容量（StatFs）；桌面等价键是 disk.system.total_bytes。|Total size of the app data volume (StatFs).|gb:1;bytes|左侧次值|Secondary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.used_bytes|磁盘|NUMBER|Byte|数据分区已用空间|Data Used|应用数据分区的已用空间（StatFs）。|Used space of the app data volume (StatFs).|gb:1;bytes|左侧主值|Primary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.available_bytes|磁盘|NUMBER|Byte|数据分区可用空间|Data Available|应用数据分区的用户可用空间（StatFs.availableBytes）。|User-available space of the app data volume (StatFs.availableBytes).|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.usage|磁盘|PROGRESS|%|数据分区使用率|Data Usage|数据分区已用空间比例（1 - available/total，与 df 口径一致）。|Used share of the app data volume.|0;0.0|右侧状态 / 圆环|Right status / ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.<字母>.used_bytes|磁盘|NUMBER|Byte|已用空间|Used Space|Windows 盘符磁盘的已用空间；Android 使用 disk.data.* 与按卷哈希键。|Used Space of a Windows drive-letter volume.|gb:1;bytes|左侧主值|Primary value|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.free_bytes|磁盘|NUMBER|Byte|可用空间|Free Space|Windows 盘符磁盘的可用空间；Android 使用 disk.data.* 与按卷哈希键。|Free Space of a Windows drive-letter volume.|gb:1;bytes|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.total_bytes|磁盘|NUMBER|Byte|总容量|Total Size|Windows 盘符磁盘的总容量；Android 使用 disk.data.* 与按卷哈希键。|Total Size of a Windows drive-letter volume.|gb:1;bytes|左侧次值|Secondary value|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.usage|磁盘|PROGRESS|%|使用率|Usage|Windows 盘符磁盘的使用率；Android 使用 disk.data.* 与按卷哈希键。|Usage of a Windows drive-letter volume.|0;0.0|右侧状态 / 圆环|Right status / ring|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.label|磁盘|TEXT||卷标|Label|Windows 盘符磁盘的卷标；Android 使用 disk.data.* 与按卷哈希键。|Label of a Windows drive-letter volume.||标题 / 左侧信息|Title / left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.filesystem|磁盘|TEXT||文件系统|Filesystem|Windows 盘符磁盘的文件系统；Android 使用 disk.data.* 与按卷哈希键。|Filesystem of a Windows drive-letter volume.||标题 / 左侧信息|Title / left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.read_bps|磁盘|UNAVAILABLE|Byte/s|读取速度|Read Speed|Windows 盘符磁盘的读取速度；Android 使用 disk.data.* 与按卷哈希键。|Read Speed of a Windows drive-letter volume.|speed|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.write_bps|磁盘|UNAVAILABLE|Byte/s|写入速度|Write Speed|Windows 盘符磁盘的写入速度；Android 使用 disk.data.* 与按卷哈希键。|Write Speed of a Windows drive-letter volume.|speed|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.io_bps|磁盘|UNAVAILABLE|Byte/s|总 I/O|Total I/O|Windows 盘符磁盘的总 I/O；Android 使用 disk.data.* 与按卷哈希键。|Total I/O of a Windows drive-letter volume.|speed|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.active_percent|磁盘|UNAVAILABLE|%|活动时间|Active Time|Windows 盘符磁盘的活动时间；Android 使用 disk.data.* 与按卷哈希键。|Active Time of a Windows drive-letter volume.|0.0|右侧状态|Right status|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.queue_length|磁盘|UNAVAILABLE|项|队列长度|Queue Length|Windows 盘符磁盘的队列长度；Android 使用 disk.data.* 与按卷哈希键。|Queue Length of a Windows drive-letter volume.|0.0|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.health|磁盘|UNAVAILABLE||健康状态|Health|Windows 盘符磁盘的健康状态；Android 使用 disk.data.* 与按卷哈希键。|Health of a Windows drive-letter volume.||状态|Status|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.temperature|磁盘|UNAVAILABLE|°C|温度|Temperature|Windows 盘符磁盘的温度；Android 使用 disk.data.* 与按卷哈希键。|Temperature of a Windows drive-letter volume.|0|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.power_on_hours|磁盘|UNAVAILABLE|小时|通电时间|Power-On Hours|Windows 盘符磁盘的通电时间；Android 使用 disk.data.* 与按卷哈希键。|Power-On Hours of a Windows drive-letter volume.|0|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.trim_status|磁盘|UNAVAILABLE||TRIM 状态|TRIM Status|Windows 盘符磁盘的TRIM 状态；Android 使用 disk.data.* 与按卷哈希键。|TRIM Status of a Windows drive-letter volume.||状态|Status|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.smart_status|磁盘|UNAVAILABLE||存储运行状态|Storage Status|Windows 盘符磁盘的存储运行状态；Android 使用 disk.data.* 与按卷哈希键。|Storage Status of a Windows drive-letter volume.||状态|Status|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.<字母>.partition_count|磁盘|UNAVAILABLE|个|关联分区数量|Partition Count|Windows 盘符磁盘的关联分区数量；Android 使用 disk.data.* 与按卷哈希键。|Partition Count of a Windows drive-letter volume.|0|左侧信息|Left info|Windows 盘符键（disk.<字母>.*）；Android 使用 disk.data.* 与按卷哈希的键。
        disk.mount_<hash>.used_bytes|磁盘|NUMBER|Byte|已用空间|Used Space|Linux 按挂载点哈希键的已用空间；Android 在 StatFs 可读的卷上发布。|Used Space of a Linux hashed mount volume.|gb:1;bytes|左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        disk.mount_<hash>.free_bytes|磁盘|NUMBER|Byte|可用空间|Free Space|Linux 按挂载点哈希键的可用空间；Android 在 StatFs 可读的卷上发布。|Free Space of a Linux hashed mount volume.|gb:1;bytes|左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        disk.mount_<hash>.total_bytes|磁盘|NUMBER|Byte|总容量|Total Size|Linux 按挂载点哈希键的总容量；Android 在 StatFs 可读的卷上发布。|Total Size of a Linux hashed mount volume.|gb:1;bytes|左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        disk.mount_<hash>.usage|磁盘|PROGRESS|%|使用率|Usage|Linux 按挂载点哈希键的使用率；Android 在 StatFs 可读的卷上发布。|Usage of a Linux hashed mount volume.|0;0.0|左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        disk.mount_<hash>.label|磁盘|TEXT||卷标|Label|Linux 按挂载点哈希键的卷标；Android 在 StatFs 可读的卷上发布。|Label of a Linux hashed mount volume.||左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        disk.mount_<hash>.filesystem|磁盘|TEXT||文件系统|Filesystem|Linux 按挂载点哈希键的文件系统；Android 在 StatFs 可读的卷上发布。|Filesystem of a Linux hashed mount volume.||左侧信息|Left info|Linux 的按挂载点哈希键；Android 在 StatFs 可读的卷上发布。
        battery.percent|电池|PROGRESS|%|电池电量|Percent|当前电池剩余电量百分比。|Current Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.remaining_mwh|电池|NUMBER|mWh|当前电池容量|Remaining mWh|当前剩余电池容量。|Current Remaining mWh, reported in mWh.|0|左侧主值|Primary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.full_mwh|电池|NUMBER|mWh|满充容量|Full mWh|电池当前可充满的容量。|Current Full mWh, reported in mWh.|0|左侧次值|Secondary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.design_mwh|电池|NUMBER|mWh|设计容量|Design mWh|电池出厂设计容量。|Current Design mWh, reported in mWh.|0|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.remaining_wh|电池|NUMBER|Wh|当前电池容量（Wh）|Remaining Wh|当前剩余电池容量，换算为 Wh。|Current Remaining Wh, reported in Wh.|0.0;0.00|左侧主值|Primary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.full_wh|电池|NUMBER|Wh|满充容量（Wh）|Full Wh|满充容量，换算为 Wh。|Current Full Wh, reported in Wh.|0.0;0.00|左侧次值|Secondary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.design_wh|电池|NUMBER|Wh|设计容量（Wh）|Design Wh|设计容量，换算为 Wh。|Current Design Wh, reported in Wh.|0.0;0.00|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.health_percent|电池|PROGRESS|%|电池健康度|Health Percent|满充容量相对设计容量的比例。|Current Health Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.ac_online|电池|BOOLEAN||外接电源状态|AC Online|当前是否接入外部电源。|Whether ac online is active or true.||标题 / 条件|Title / Condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.charging|电池|BOOLEAN||正在充电|Charging|当前是否正在充电。|Whether charging is active or true.||标题 / 条件|Title / Condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.discharging|电池|BOOLEAN||正在放电|Discharging|当前是否正在使用电池放电。|Whether discharging is active or true.||标题 / 条件|Title / Condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.rate_watts|电池|NUMBER|W|电池实时功率|Rate Watts|当前充电或放电功率的绝对值。不同机型可能不提供。|Current Rate Watts, reported in W.|0.0;0.00|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.charge_rate_watts|电池|NUMBER|W|充电功率|Charge Rate Watts|当前充电功率；未充电时通常为 0。|Current Charge Rate Watts, reported in W.|0.0;0.00|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.discharge_rate_watts|电池|NUMBER|W|放电功率|Discharge Rate Watts|当前放电功率；未放电时通常为 0。|Current Discharge Rate Watts, reported in W.|0.0;0.00|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.voltage_mv|电池|NUMBER|mV|电池电压（mV）|Voltage Mv|Windows 电池接口报告的实时电压。不同机型可能不提供。|Current Voltage Mv, reported in mV.|0|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.voltage_v|电池|NUMBER|V|电池电压（V）|Voltage V|电池实时电压，换算为伏特。|Current Voltage V, reported in V.|0.00|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.time_remaining_seconds|电池|NUMBER|秒|预计剩余使用时间|Time Remaining Seconds|Windows 估算的剩余电池使用时间；无法估算时为 0。|Current Time Remaining Seconds, reported in s.|duration|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.time_remaining_text|电池|TEXT||预计剩余使用时间文字|Time Remaining Text|预计剩余使用时间，已格式化为 HH:MM:SS；无法估算时显示“未知”。|Current Time Remaining Text.||左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.full_life_seconds|电池|NUMBER|秒|预计满电续航|Full Life Seconds|Windows 报告的满电预计续航时间；不可用时为 0。|Current Full Life Seconds, reported in s.|duration|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.saver_on|电池|BOOLEAN||节电模式状态|Saver On|Windows 电池节电模式是否开启。|Whether saver on is active or true.||标题 / 条件|Title / Condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.status_text|电池|TEXT||电池状态文字|Status Text|根据电源状态生成“充电中 / 使用电池 / 已接通电源 / 未知”等文字。|Current Status Text.||标题 / 右侧状态|Title / Right status|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.power_source|电池|TEXT||当前电源来源|Power Source|当前电源来源：“交流电源”或“电池”。|Current Power Source.||标题 / 左侧信息|Title / Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.cycle_count|电池|NUMBER|次|电池循环次数|Cycle Count|固件/驱动提供的电池循环次数；部分设备可能返回 0。|Current Cycle Count.|0|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.estimated_time_to_empty|电池|NUMBER|秒|预计耗尽剩余时间|Estimated Time To Empty|Windows 电源管理估算的距离电池耗尽剩余秒数；系统无法估算时变量不可用。|Current Estimated Time To Empty, reported in s.|duration;duration-long|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.estimated_time_to_full|电池|NUMBER|秒|预计充满剩余时间|Estimated Time To Full|根据当前剩余容量、满充容量和实时充电功率计算的预计充满时间；仅充电功率可用时提供。|Current Estimated Time To Full, reported in s.|duration;duration-long|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.design_vs_current_health|电池|PROGRESS|%|设计容量健康度|Design Vs Current Health|当前满充容量相对设计容量的比例，与电池健康度一致。|Current Design Vs Current Health, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.temperature|电池|NUMBER|°C|电池温度|Temperature|通过 Windows BatteryTemperature 接口读取；仅驱动/固件提供时可用。|Current Temperature, reported in °C.|0.0|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.chemistry|电池|TEXT||电池化学类型|Chemistry|Win32_Battery 报告的电池化学体系，如锂离子/锂聚合物。|Current Chemistry.||左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.time_to_full_seconds|电池|NUMBER|秒|充满剩余时间|Time To Full Seconds|预计充满剩余时间；仅系统能提供估算时显示。|Current Time To Full Seconds, reported in s.|duration|左侧信息|Left info|Android 不提供剩余时间估算 API；仅在电流与容量均为真实值时推导，否则发布状态文字。
        battery.charge_counter|电池|NUMBER|µAh|充电计数器|Charge Counter|Android BatteryManager 的电荷计数器；不支持时返回 Integer.MIN_VALUE，按缺失处理。|Current Charge Counter, reported in µAh.|0;duration|左侧信息|Left info|BATTERY_PROPERTY_CHARGE_COUNTER（µAh）；不支持时返回 Integer.MIN_VALUE。
        battery.current_ma|电池|NUMBER|mA|电池电流|Battery Current|Android 电池瞬时电流；充电/放电方向按 EXTRA_STATUS 判定，不依赖符号。|Current Battery Current, reported in mA.|0;0.0|左侧信息|Left info|BATTERY_PROPERTY_CURRENT_NOW（µA）或 CURRENT_AVERAGE；充/放电方向按 EXTRA_STATUS 判定，不按符号。
        battery.health|电池|TEXT||电池健康状态码|Battery Health|Android EXTRA_HEALTH 状态码（GOOD/OVERHEAT/DEAD/...）；百分比健康度请用 battery.health_percent。|Android EXTRA_HEALTH status code (GOOD/OVERHEAT/DEAD/...).||标题 / 条件|Title / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.plugged|电池|TEXT||电源接入方式|Plugged|Android EXTRA_PLUGGED：AC / USB / WIRELESS / 未接入。|Android EXTRA_PLUGGED: AC / USB / WIRELESS / none.||标题 / 条件|Title / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.status|电池|NUMBER||电池状态码|Battery Status|Android EXTRA_STATUS 数值（CHARGING/DISCHARGING/FULL/NOT_CHARGING）。|Android EXTRA_STATUS code (CHARGING/DISCHARGING/FULL/NOT_CHARGING).|0|状态 / 条件|Status / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.temperature_c|电池|NUMBER|°C|电池温度|Battery Temperature|Android 电池温度，摄氏；桌面目录中的同义键是 battery.temperature。|Battery temperature in °C; the desktop catalog spells this battery.temperature.|0;0.0|左侧信息|Left info|ACTION_BATTERY_CHANGED EXTRA_TEMPERATURE（0.1 °C）。
        network.download_bps|网络|NUMBER|Byte/s|系统总下载速度|Download B/s|所有已连接、非回环网络接口合计的实时下载速度。|Current Download B/s, reported in Byte/s.|speed|左侧主值|Primary value|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.upload_bps|网络|NUMBER|Byte/s|系统总上传速度|Upload B/s|所有已连接、非回环网络接口合计的实时上传速度。|Current Upload B/s, reported in Byte/s.|speed|左侧次值|Secondary value|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.total_bps|网络|NUMBER|Byte/s|系统总吞吐量|Total B/s|系统总下载速度与总上传速度之和。|Current Total B/s, reported in Byte/s.|speed|状态计算|Status calculation|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.download_mbps|网络|NUMBER|Mbps|系统总下载速度（Mbps）|Download Mbps|系统总下载速度，换算为 Mbps。|Current Download Mbps, reported in Mbps.|0.0;0.00|左侧主值|Primary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.upload_mbps|网络|NUMBER|Mbps|系统总上传速度（Mbps）|Upload Mbps|系统总上传速度，换算为 Mbps。|Current Upload Mbps, reported in Mbps.|0.0;0.00|左侧次值|Secondary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.total_mbps|网络|NUMBER|Mbps|系统总吞吐量（Mbps）|Total Mbps|下载与上传合计，换算为 Mbps。|Current Total Mbps, reported in Mbps.|0.0;0.00|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.display_download|网络|TEXT||方案下载速度文字|Display Download|按当前网络方案选择的单位生成的下载速度文字。|Current Display Download.||左侧主值|Primary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.display_upload|网络|TEXT||方案上传速度文字|Display Upload|按当前网络方案选择的单位生成的上传速度文字。|Current Display Upload.||左侧次值|Secondary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent|网络|PROGRESS|%|方案网络百分比|Profile Percent|按方案选择的百分比模式与“100% 对应速度”计算。|Current Profile Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent_text|网络|TEXT||方案网络百分比文字|Profile Percent Text|根据模式生成“50% / ↓ 50% / ↑ 50%”等文字；较大值模式不显示箭头。|Current Profile Percent Text.||右侧状态|Right status|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent_bps|网络|NUMBER|Byte/s|百分比计算速度|Profile Percent B/s|当前网络方案实际用于计算百分比的速度。|Current Profile Percent B/s, reported in Byte/s.|speed|状态计算|Status calculation|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent_mode|网络|TEXT||网络百分比模式|Profile Percent Mode|当前方案百分比模式：Total / Download / Upload / Max。|Current Profile Percent Mode.||标题 / 条件|Title / Condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.link_speed_bps|网络|UNAVAILABLE|bit/s|总链路速率|Link Speed B/s|所有活动网络接口报告的链路速率之和。|Current Link Speed B/s, reported in bit/s.|0|左侧信息|Left info|Android 不公开链路速率（WifiInfo.getLinkSpeed 自 API 30 起弃用且仅限 Wi-Fi）。
        network.max_link_speed_bps|网络|UNAVAILABLE|bit/s|最高单接口链路速率|Max Link Speed B/s|所有活动网络接口中最高的单个链路速率。|Current Max Link Speed B/s, reported in bit/s.|0|左侧信息|Left info|Android 不公开链路速率（WifiInfo.getLinkSpeed 自 API 30 起弃用且仅限 Wi-Fi）。
        network.utilization_percent|网络|UNAVAILABLE|%|网络链路利用率|Utilization Percent|系统总吞吐量与活动接口总链路速率的近似比例。|Current Utilization Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 不公开链路速率（WifiInfo.getLinkSpeed 自 API 30 起弃用且仅限 Wi-Fi）。
        network.total_received_bytes|网络|NUMBER|Byte|累计接收流量|Total Received Bytes|当前开机期间所有活动网络接口累计接收字节数之和。|Current Total Received Bytes, reported in Byte.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.total_sent_bytes|网络|NUMBER|Byte|累计发送流量|Total Sent Bytes|当前开机期间所有活动网络接口累计发送字节数之和。|Current Total Sent Bytes, reported in Byte.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.total_transferred_bytes|网络|NUMBER|Byte|累计总流量|Total Transferred Bytes|累计接收与发送流量之和。|Current Total Transferred Bytes, reported in Byte.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.active_interface_count|网络|NUMBER|个|活动网络接口数量|Active Interface Count|当前处于 Up 状态且非回环的网络接口数量。|Current Active Interface Count.|0|左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.interface_names|网络|TEXT||活动网络接口名称|Interface Names|所有活动网络接口名称，以逗号分隔。|Current Interface Names.||标题 / 左侧信息|Title / Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.interface_types|网络|TEXT||活动网络接口类型|Interface Types|活动网络接口类型列表，如 Wireless80211 / Ethernet。|Current Interface Types.||标题 / 左侧信息|Title / Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.ipv4_addresses|网络|TEXT||本机 IPv4 地址|IPv4 Addresses|活动网络接口上的 IPv4 单播地址列表。|Current IPv4 Addresses.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.ipv6_addresses|网络|TEXT||本机 IPv6 地址|IPv6 Addresses|活动网络接口上的 IPv6 单播地址列表。|Current IPv6 Addresses.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.default_gateways|网络|TEXT||默认网关|Default Gateways|活动网络接口配置的网关地址列表。|Current Default Gateways.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.dns_servers|网络|TEXT||DNS 服务器|DNS Servers|活动网络接口配置的 DNS 服务器地址列表。|Current DNS Servers.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.available|网络|BOOLEAN||网络可用状态|Available|Windows 是否检测到至少一个可用网络连接；不代表一定可访问互联网。|Whether available is active or true.||标题 / 条件|Title / Condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.packets_received|网络|NUMBER|包|累计接收数据包|Packets Received|活动网络接口累计接收单播数据包数量。|Current Packets Received, reported in 包.|0|左侧信息|Left info|TrafficStats.getTotalRxPackets/getTotalTxPackets；按同样的单调与回绕保护处理。
        network.packets_sent|网络|NUMBER|包|累计发送数据包|Packets Sent|活动网络接口累计发送单播数据包数量。|Current Packets Sent, reported in 包.|0|左侧信息|Left info|TrafficStats.getTotalRxPackets/getTotalTxPackets；按同样的单调与回绕保护处理。
        network.receive_errors|网络|UNAVAILABLE|个|接收错误|Receive Errors|活动网络接口累计接收错误数量。|Current Receive Errors, reported in count.|0|状态 / 调试|Status / Debug|TrafficStats 不提供错误计数器。
        network.send_errors|网络|UNAVAILABLE|个|发送错误|Send Errors|活动网络接口累计发送错误数量。|Current Send Errors, reported in count.|0|状态 / 调试|Status / Debug|TrafficStats 不提供错误计数器。
        network.signal_dbm|网络|NUMBER|dBm|Wi-Fi 信号强度|Signal dBm|根据 Windows WLAN 信号质量换算的近似 dBm；有 Wi-Fi 连接时可用。|Current Signal dBm, reported in dBm.|0|左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.public_ipv4|网络|TEXT||公网 IPv4|Public IPv4|通过 api.ipify.org 查询的公网 IPv4，5 分钟缓存；仅使用该变量时才发起请求。|Current Public IPv4.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.public_ipv6|网络|TEXT||公网 IPv6|Public IPv6|通过 api6.ipify.org 查询的公网 IPv6，5 分钟缓存；没有 IPv6 时变量不可用。|Current Public IPv6.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.vpn_status|网络|BOOLEAN||VPN 状态|VPN Status|根据活动 Tunnel/PPP/WireGuard/Wintun 等网络接口检测 VPN 是否活动。|Current VPN Status.||状态 / 条件|Status / Condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.vpn_name|网络|TEXT||VPN 名称|VPN Name|当前检测到的活动 VPN 接口名称。|Name reported for VPN.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.proxy_status|网络|BOOLEAN||系统代理状态|Proxy Status|当前用户 Internet Settings 的 ProxyEnable 状态。|Current Proxy Status.||状态 / 条件|Status / Condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.proxy_address|网络|TEXT||系统代理地址|Proxy Address|当前用户配置的 ProxyServer。|Current Proxy Address.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.dns_latency_ms|网络|NUMBER|ms|系统 DNS 解析耗时|DNS Latency Ms|本机系统解析 one.one.one.one 的耗时；可能受 DNS 缓存影响。|Measured DNS Latency Ms (ms).|0.0|右侧状态 / 圆环|Right status / Ring|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.tcp_connections|网络|UNAVAILABLE|条|活动 TCP 连接数|TCP Connections|IPGlobalProperties 返回的活动 TCP 连接数量。|Current TCP Connections, reported in count.|0|左侧信息|Left info|TrafficStats 不提供错误计数器。
        network.udp_connections|网络|UNAVAILABLE|个|UDP 监听端点数|UDP Connections|IPGlobalProperties 返回的活动 UDP 监听端点数量。|Current UDP Connections, reported in count.|0|左侧信息|Left info|TrafficStats 不提供错误计数器。
        network.wifi_channel|网络|NUMBER||Wi-Fi 信道|Wi-Fi Channel|当前 WLAN 接口的无线信道。|Current Wi-Fi Channel.|0|左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.wifi_band|网络|TEXT||Wi-Fi 频段|Wi-Fi Band|当前 WLAN 接口的频段；新系统直接读取 Band，旧系统在可推断时回退。|Current Wi-Fi Band.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.wifi_standard|网络|TEXT||Wi-Fi 标准|Wi-Fi Standard|当前 WLAN Radio type，例如 802.11ax。|Current Wi-Fi Standard.||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.connected|网络|BOOLEAN||网络已连接|Network Connected|ConnectivityManager 报告的当前默认网络是否可用。|Whether the current default network is available.||标题 / 条件|Title / condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.type|网络|TEXT||网络类型|Network Type|当前传输类型：WIFI / CELLULAR / ETHERNET / VPN / NONE。|Current transport: WIFI / CELLULAR / ETHERNET / VPN / NONE.||标题 / 左侧信息|Title / left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.interface|网络|TEXT||网络接口名|Network Interface|当前默认网络的接口名（LinkProperties.interfaceName）。|Interface name of the default network (LinkProperties.interfaceName).||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.wifi_ssid|网络|TEXT||Wi-Fi SSID|Wi-Fi SSID|当前 Wi-Fi SSID；Android 8.1+ 需要位置权限，缺失时发布状态文字。|Current Wi-Fi SSID; location permission is required on Android 8.1+.||标题 / 左侧信息|Title / left info|WifiInfo.getSSID()；Android 8.1+ 需要位置权限，权限缺失时发布状态文字。
        network.download_total_bytes|网络|NUMBER|Byte|累计下载流量|Total Downloaded|TrafficStats 累计接收字节（开机以来）；桌面同义键是 network.total_received_bytes。|TrafficStats total received bytes since boot.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.upload_total_bytes|网络|NUMBER|Byte|累计上传流量|Total Uploaded|TrafficStats 累计发送字节（开机以来）；桌面同义键是 network.total_sent_bytes。|TrafficStats total sent bytes since boot.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        probe.target|网络探测|TEXT||探测地址|Target|当前网络包探测器使用的 IPv4、IPv6 或域名。|Current Target.||左侧信息 / 标题|Left info / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.address|网络探测|TEXT||实际响应地址|Address|目标解析后实际参与检测或返回响应的 IP 地址。|Current Address.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.protocol|网络探测|TEXT||检测协议|Protocol|当前使用的检测协议：ICMP、TCP 或 UDP。|Current Protocol.||左侧信息 / 标题|Left info / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.port|网络探测|NUMBER||检测端口|Port|TCP / UDP 检测端口；ICMP 模式返回 0。|Current Port.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.endpoint|网络探测|TEXT||探测端点|Endpoint|ICMP 显示目标地址；TCP / UDP 显示“地址:端口”。|Current Endpoint.||左侧信息 / 标题|Left info / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.online|网络探测|BOOLEAN||探测是否成功|Online|最近一次网络探测是否成功。|Whether online is active or true.||状态 / 条件|Status / Condition|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.status_text|网络探测|TEXT||探测状态文字|Status Text|最近一次探测状态，例如“在线”“超时”“端口不可达”。|Current Status Text.||右侧状态 / 标题|Right status / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.reply_status|网络探测|TEXT||探测原始状态|Reply Status|协议探测返回的底层状态，例如 Success、Connected、Response、TimedOut。|Current Reply Status.||状态 / 调试|Status / Debug|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.latency_ms|网络探测|NUMBER|ms|探测延迟|Latency Ms|最近一次成功探测的往返/连接延迟；失败时按 999 ms 处理。|Measured Latency Ms (ms).|0;0.0|左侧主体信息|Left main info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.latency_text|网络探测|TEXT||探测延迟文字|Latency Text|成功时显示“20ms”；失败时显示超时或错误状态。|Measured Latency Text.||左侧主体信息|Left main info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.latency_progress|网络探测|PROGRESS|%|延迟进度|Latency Progress|0 ms 为 0%，999 ms 及以上为 100%；适合用作延迟圆环。|Measured Latency Progress (%).|0;0.0|圆环|Ring|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.full_scale_ms|网络探测|NUMBER|ms|延迟 100% 对应值|Full Scale Ms|延迟圆环 100% 对应 999 ms。|Current Full Scale Ms, reported in ms.|0|状态计算|Status calculation|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.timeout_ms|网络探测|NUMBER|ms|单次探测超时|Timeout Ms|单次 ICMP/TCP/UDP 探测等待响应的超时时间。|Current Timeout Ms, reported in ms.|0|状态计算|Status calculation|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.ttl|网络探测|UNAVAILABLE||ICMP TTL|Ttl|最近一次 ICMP 成功响应的 TTL；TCP/UDP 为 0。|Current Ttl.|0|左侧信息|Left info|Android 无公开 API 读取 ICMP TTL；与 Linux/macOS 版一致，该键不支持。
        probe.sent|网络探测|NUMBER|次|探测样本数|Sent|当前目标最近滚动统计窗口内的探测样本数量。|Current Sent, reported in count.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.received|网络探测|NUMBER|次|成功样本数|Received|当前目标最近滚动统计窗口内成功的探测次数。|Current Received, reported in count.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.lost|网络探测|NUMBER|次|失败样本数|Lost|当前目标最近滚动统计窗口内失败/超时次数。|Current Lost, reported in count.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.loss_percent|网络探测|PROGRESS|%|丢包率|Loss Percent|当前地址、协议和端口组合最近 20 次探测的失败/超时比例。|Current Loss Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.avg_latency_ms|网络探测|NUMBER|ms|平均延迟|Avg Latency Ms|当前探测配置最近 20 次成功样本的平均延迟。|Measured Avg Latency Ms (ms).|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.min_latency_ms|网络探测|NUMBER|ms|最低延迟|Min Latency Ms|当前探测配置最近 20 次成功样本中的最低延迟。|Measured Min Latency Ms (ms).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.max_latency_ms|网络探测|NUMBER|ms|最高延迟|Max Latency Ms|当前探测配置最近 20 次成功样本中的最高延迟。|Measured Max Latency Ms (ms).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.jitter_ms|网络探测|NUMBER|ms|延迟抖动|Jitter Ms|相邻成功样本延迟差的平均绝对值，用于近似表示抖动。|Current Jitter Ms, reported in ms.|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.last_success|网络探测|TEXT||最近成功时间|Last Success|当前探测配置最近一次成功的本地时间。|Current Last Success.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.error|网络探测|TEXT||探测错误|Error|最近一次检测失败时的底层状态或错误。|Current Error.||状态 / 调试|Status / Debug|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.dns_resolve_time|网络探测|NUMBER|ms|DNS 解析耗时|DNS Resolve Time|当检测地址为域名时，当前探测周期的 DNS 解析耗时；直接 IP 时为 0。|Current DNS Resolve Time, reported in ms.|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.tcp_connect_time|网络探测|NUMBER|ms|TCP 建连耗时|TCP Connect Time|TCP 模式下从发起连接到连接成功的耗时。|Current TCP Connect Time, reported in ms.|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.status|网络探测|TEXT||探测状态|Probe Status|最近一次探测状态；桌面目录中的同义键是 probe.status_text。|Latest probe status; the desktop catalog spells this probe.status_text.||右侧状态 / 标题|Right status / title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.target|Ping（兼容）|TEXT||Ping 目标|Target|当前方案正在检测的 IP 地址或域名。|Measured Target.||左侧主值 / 标题|Primary value / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.address|Ping（兼容）|TEXT||Ping 实际地址|Address|Ping 响应返回的实际 IP 地址；域名目标解析成功后可查看最终地址。|Measured Address.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.protocol|Ping（兼容）|TEXT||Ping/探测协议|Protocol|兼容变量：当前网络探测协议 ICMP、TCP 或 UDP。|Measured Protocol.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.port|Ping（兼容）|NUMBER||Ping/探测端口|Port|兼容变量：TCP / UDP 端口；ICMP 为 0。|Measured Port.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.endpoint|Ping（兼容）|TEXT||Ping/探测端点|Endpoint|兼容变量：当前地址和端口组合。|Measured Endpoint.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.online|Ping（兼容）|BOOLEAN||Ping 是否在线|Online|最近一次 ICMP Ping 是否成功。|Measured Online.||状态 / 条件|Status / Condition|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.status_text|Ping（兼容）|TEXT||Ping 状态文字|Status Text|最近一次检测状态，例如“在线”“超时”“不可达”。|Measured Status Text.||右侧状态 / 标题|Right status / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.reply_status|Ping（兼容）|TEXT||Ping 原始状态|Reply Status|System.Net.NetworkInformation.IPStatus 返回的原始状态名称。|Measured Reply Status.||状态 / 调试|Status / Debug|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.latency_ms|Ping（兼容）|NUMBER|ms|Ping 延迟|Latency Ms|最近一次成功 Ping 的往返延迟；失败时按 999 ms 计入进度。|Measured Latency Ms (ms).|0;0.0|右侧状态|Right status|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.latency_text|Ping（兼容）|TEXT||Ping 延迟文字|Latency Text|成功时显示“20ms”一类文字；超时或不可达时显示对应状态。|Measured Latency Text.||右侧状态|Right status|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.progress|Ping（兼容）|PROGRESS|%|Ping 延迟进度|Progress|延迟换算后的圆环进度：0 ms 为 0%，999 ms 及以上为 100%；超时/失败为 100%。|Measured Progress (%).|0;0.0|右侧状态 / 圆环|Right status / Ring|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.full_scale_ms|Ping（兼容）|NUMBER|ms|Ping 100% 对应延迟|Full Scale Ms|Ping 圆环 100% 对应的延迟，固定为 999 ms。|Measured Full Scale Ms (ms).|0|状态计算|Status calculation|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.timeout_ms|Ping（兼容）|NUMBER|ms|Ping 超时时间|Timeout Ms|单次 Ping 等待响应的超时时间。|Measured Timeout Ms (ms).|0|状态计算|Status calculation|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.ttl|Ping（兼容）|UNAVAILABLE||Ping TTL|Ttl|最近一次成功响应返回的 TTL。|Measured Ttl.|0|左侧信息|Left info|Android 无公开 API 读取 ICMP TTL；与 Linux/macOS 版一致，该键不支持。
        ping.sent|Ping（兼容）|NUMBER|次|Ping 统计发送次数|Sent|当前目标最近滚动统计窗口内的 Ping 样本数量。|Measured Sent (count).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.received|Ping（兼容）|NUMBER|次|Ping 统计成功次数|Received|当前目标最近滚动统计窗口内成功的 Ping 样本数量。|Measured Received (count).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.lost|Ping（兼容）|NUMBER|次|Ping 丢包次数|Lost|当前目标最近滚动统计窗口内失败/超时的 Ping 样本数量。|Measured Lost (count).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.loss_percent|Ping（兼容）|PROGRESS|%|Ping 丢包率|Loss Percent|当前目标最近 20 次检测的丢包率。|Measured Loss Percent (%).|0;0.0|右侧状态 / 圆环|Right status / Ring|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.avg_latency_ms|Ping（兼容）|NUMBER|ms|Ping 平均延迟|Avg Latency Ms|当前目标最近 20 次成功检测的平均延迟。|Measured Avg Latency Ms (ms).|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.min_latency_ms|Ping（兼容）|NUMBER|ms|Ping 最低延迟|Min Latency Ms|当前目标最近 20 次成功检测中的最低延迟。|Measured Min Latency Ms (ms).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.max_latency_ms|Ping（兼容）|NUMBER|ms|Ping 最高延迟|Max Latency Ms|当前目标最近 20 次成功检测中的最高延迟。|Measured Max Latency Ms (ms).|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.jitter_ms|Ping（兼容）|NUMBER|ms|Ping 抖动|Jitter Ms|当前目标最近成功样本相邻延迟差的平均绝对值，用于近似表示网络抖动。|Measured Jitter Ms (ms).|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.last_success|Ping（兼容）|TEXT||Ping 最近成功时间|Last Success|当前目标最近一次成功响应的本地时间。|Measured Last Success.||左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        ping.error|Ping（兼容）|TEXT||Ping 错误信息|Error|最近一次检测失败时的错误或状态说明。|Measured Error.||状态 / 调试|Status / Debug|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        system.time|系统|TEXT||当前时间|Time|本机当前时间，精确到秒。|Current Time.||左侧信息 / 标题|Left info / Title|
        system.date|系统|TEXT||当前日期|Date|本机当前日期。|Current Date.||左侧信息 / 标题|Left info / Title|
        system.datetime|系统|TEXT||当前日期时间|Datetime|本机当前日期与时间。|Current Datetime.||左侧信息 / 标题|Left info / Title|
        system.uptime_seconds|系统|NUMBER|秒|系统运行时间|Uptime Seconds|当前 Windows 自开机以来运行的秒数。|Current Uptime Seconds, reported in s.|duration|左侧信息|Left info|
        system.uptime_text|系统|TEXT||系统运行时间文字|Uptime Text|系统运行时间的可读文字。|Current Uptime Text.||左侧信息|Left info|
        system.boot_time|系统|TEXT||系统启动时间|Boot Time|根据系统运行时间估算的本次 Windows 启动时间。|Current Boot Time.||左侧信息|Left info|
        system.machine_name|系统|TEXT||计算机名称|Machine Name|Windows 计算机名称。|Name reported for Machine.||标题 / 左侧信息|Title / Left info|
        system.host_name|系统|TEXT||主机名|Host Name|DNS 主机名。|Name reported for Host.||标题 / 左侧信息|Title / Left info|
        system.user_name|系统|TEXT||当前用户名|User Name|当前 Windows 用户名。|Name reported for User.||标题 / 左侧信息|Title / Left info|
        system.user_domain|系统|UNAVAILABLE||当前用户域|User Domain|当前用户所属 Windows 域/计算机名。|Current User Domain.||标题 / 左侧信息|Title / Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.os_description|系统|TEXT||操作系统名称|OS Description|.NET RuntimeInformation 提供的 Windows 操作系统描述。|Current OS Description.||标题 / 左侧信息|Title / Left info|
        system.os_version|系统|TEXT||操作系统版本|OS Version|Windows 操作系统版本号。|Current OS Version.||左侧信息|Left info|
        system.os_build|系统|UNAVAILABLE||Windows Build|OS Build|当前 Windows Build 号。|Current OS Build.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.os_architecture|系统|TEXT||操作系统架构|OS Architecture|当前 Windows 架构，如 X64 / Arm64。|Current OS Architecture.||左侧信息|Left info|
        system.process_architecture|系统|TEXT||应用进程架构|Process Architecture|Endfield Charge Plus 当前进程架构。|Current Process Architecture.||左侧信息|Left info|
        system.framework_version|系统|TEXT||.NET 版本|Framework Version|当前进程使用的 .NET FrameworkDescription。|Current Framework Version.||左侧信息|Left info|
        system.processor_count|系统|NUMBER|个|系统逻辑处理器数|Processor Count|Environment.ProcessorCount。|Current Processor Count.|0|左侧信息|Left info|
        system.timezone_id|系统|TEXT||时区 ID|Timezone ID|当前本地时区 ID。|Current Timezone ID.||标题 / 左侧信息|Title / Left info|
        system.timezone_name|系统|TEXT||时区名称|Timezone Name|当前本地时区显示名称。|Name reported for Timezone.||标题 / 左侧信息|Title / Left info|
        system.utc_offset_hours|系统|NUMBER|小时|UTC 时差|UTC Offset Hours|当前时区相对 UTC 的小时偏移。|Current UTC Offset Hours, reported in h.|0.0|左侧信息|Left info|
        system.culture|系统|TEXT||系统区域语言|Culture|当前进程文化区域名称，如 zh-CN。|Current Culture.||左侧信息|Left info|
        system.power_plan|系统|UNAVAILABLE||电源计划 GUID|Power Plan|powercfg 当前活动电源方案 GUID。|Current Power Plan.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.power_plan_name|系统|UNAVAILABLE||电源计划名称|Power Plan Name|当前 Windows 活动电源计划名称。|Name reported for Power Plan.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.bios_version|系统|UNAVAILABLE||BIOS 版本|BIOS Version|Win32_BIOS SMBIOSBIOSVersion。|Current BIOS Version.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.bios_date|系统|UNAVAILABLE||BIOS 日期|BIOS Date|Win32_BIOS ReleaseDate。|Current BIOS Date.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.motherboard_manufacturer|系统|UNAVAILABLE||主板制造商|Motherboard Manufacturer|Win32_BaseBoard Manufacturer。|Current Motherboard Manufacturer.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.motherboard_model|系统|UNAVAILABLE||主板型号|Motherboard Model|Win32_BaseBoard Product。|Current Motherboard Model.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.motherboard_temperature|系统|UNAVAILABLE|°C|主板最高温度|Motherboard Temperature|LibreHardwareMonitor 可读取的主板温度传感器最高值。|Current Motherboard Temperature, reported in °C.|0.0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.fan_speed|系统|UNAVAILABLE|RPM|风扇最高转速|Fan Speed|LibreHardwareMonitor 当前可见风扇的最高 RPM。|Current Fan Speed, reported in RPM.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.fan_speed_percent|系统|UNAVAILABLE|%|风扇控制百分比|Fan Speed Percent|硬件监控 Control 类型传感器的最高百分比。|Current Fan Speed Percent, reported in %.|0|右侧状态 / 圆环|Right status / Ring|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.update_pending|系统|UNAVAILABLE||Windows 更新待处理|Update Pending|检测可用 Windows 更新以及 Windows Update / Component Based Servicing 的待重启状态。|Whether update pending is active or true.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.update_last_installed|系统|UNAVAILABLE||最近安装更新日期|Update Last Installed|Win32_QuickFixEngineering 中最近的 InstalledOn。|Current Update Last Installed.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.defender_status|系统|UNAVAILABLE||Microsoft Defender 状态|Defender Status|Windows Defender WMI 报告的启用/实时保护状态。|Current Defender Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.firewall_status|系统|UNAVAILABLE||Windows 防火墙状态|Firewall Status|Windows Firewall Policy 当前活动配置状态。|Current Firewall Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.bitlocker_status|系统|UNAVAILABLE||系统盘 BitLocker 状态|Bitlocker Status|Windows Volume Encryption 接口报告的系统盘保护状态。|Current Bitlocker Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.hyper_v_status|系统|UNAVAILABLE||Hyper-V 状态|Hyper V Status|Windows OptionalFeature 中 Microsoft-Hyper-V-All 是否安装启用。|Current Hyper V Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.wsl_status|系统|UNAVAILABLE||WSL 状态|WSL Status|检测 WSL 可执行文件/已注册发行版。|Current WSL Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.wsl_distro_count|系统|UNAVAILABLE|个|WSL 发行版数量|WSL Distro Count|当前用户已注册的 WSL 发行版数量。|Current WSL Distro Count.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        system.kernel_version|系统|TEXT||内核版本|Kernel Version|Linux/Android 内核版本（os.version / uname）。|Current Kernel Version.||左侧信息|Left info|Android 系统只读属性（Build、System、Runtime）。
        system.reboot_required|系统|UNAVAILABLE||需要重启|Reboot Required|Linux 发行版的重启标记；Android 无对应概念。|Whether reboot required is active or true.||标题 / 条件|Title / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        device.model|系统|TEXT||设备型号|Device Model|Build.MODEL 设备型号。|Build.MODEL device model.||标题 / 左侧信息|Title / left info|Android Build / PowerManager / Settings 只读系统属性。
        device.manufacturer|系统|TEXT||设备制造商|Device Manufacturer|Build.MANUFACTURER 制造商。|Build.MANUFACTURER.||标题 / 左侧信息|Title / left info|Android Build / PowerManager / Settings 只读系统属性。
        device.android_version|系统|TEXT||Android 版本|Android Version|Build.VERSION.RELEASE。|Build.VERSION.RELEASE.||左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.sdk|系统|NUMBER||API 级别|API Level|Build.VERSION.SDK_INT。|Build.VERSION.SDK_INT.|0|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.uptime_seconds|系统|NUMBER|秒|开机时长|Device Uptime|SystemClock.elapsedRealtime()/1000（不含深度睡眠）。|SystemClock.elapsedRealtime()/1000.|duration|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.screen_state|系统|TEXT||屏幕状态|Screen State|PowerManager.isInteractive / KEYGUARD：ON / OFF / LOCKED。|Screen state: ON / OFF / LOCKED.||标题 / 条件|Title / condition|Android Build / PowerManager / Settings 只读系统属性。
        device.brightness|系统|NUMBER||屏幕亮度|Screen Brightness|Settings.System.SCREEN_BRIGHTNESS（0-255）；不假设百分比。|Settings.System.SCREEN_BRIGHTNESS (0-255).|0|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        display.primary_width_px|显示器|NUMBER|px|主显示器宽度|Primary Width Px|Windows 主显示器宽度。|Current Primary Width Px, reported in px.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.primary_height_px|显示器|NUMBER|px|主显示器高度|Primary Height Px|Windows 主显示器高度。|Current Primary Height Px, reported in px.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.virtual_x_px|显示器|UNAVAILABLE|px|虚拟桌面 X 起点|Virtual X Px|多显示器虚拟桌面的左边界坐标。|Current Virtual X Px, reported in px.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_y_px|显示器|UNAVAILABLE|px|虚拟桌面 Y 起点|Virtual Y Px|多显示器虚拟桌面的上边界坐标。|Current Virtual Y Px, reported in px.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_width_px|显示器|UNAVAILABLE|px|虚拟桌面宽度|Virtual Width Px|多显示器虚拟桌面总宽度。|Current Virtual Width Px, reported in px.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_height_px|显示器|UNAVAILABLE|px|虚拟桌面高度|Virtual Height Px|多显示器虚拟桌面总高度。|Current Virtual Height Px, reported in px.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.monitor_count|显示器|UNAVAILABLE|台|显示器数量|Monitor Count|Windows 当前检测到的显示器数量。|Current Monitor Count.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.system_dpi|显示器|NUMBER|DPI|系统 DPI|System Dpi|Windows 系统 DPI。|Current System Dpi, reported in DPI.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.scale_percent|显示器|PROGRESS|%|系统缩放比例|Scale Percent|根据系统 DPI 换算的显示缩放百分比。|Current Scale Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.primary.name|显示器|TEXT||主显示设备名称|Primary Name|Windows 当前活动显示设备名称。|Name reported for Primary.||左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.primary.color_depth|显示器|NUMBER|bit|主显示器色深|Primary Color Depth|EnumDisplaySettings 返回的 BitsPerPel。|Current Primary Color Depth, reported in bit.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.primary.refresh_rate|显示器|NUMBER|Hz|主显示器刷新率|Primary Refresh Rate|当前显示模式刷新率。|Current Primary Refresh Rate, reported in Hz.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.secondary.name|显示器|TEXT||第二显示器名称|Secondary Name|检测到第二个活动显示设备时提供。|Name reported for Secondary.||左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.secondary.resolution|显示器|TEXT||第二显示器分辨率|Secondary Resolution|第二显示器当前分辨率。|Current Secondary Resolution.||左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.secondary.refresh_rate|显示器|NUMBER|Hz|第二显示器刷新率|Secondary Refresh Rate|第二显示器当前刷新率。|Current Secondary Refresh Rate, reported in Hz.|0|左侧信息|Left info|通过 WindowMetrics / DisplayMetrics 读取当前逻辑显示器；dpi 使用 densityDpi。
        display.virtual_x_points|显示器|UNAVAILABLE|pt|桌面左边界|Virtual X Points|CoreGraphics 全局桌面左边界（逻辑点）。|Current Virtual X Points, reported in pt.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_y_points|显示器|UNAVAILABLE|pt|桌面上边界|Virtual Y Points|CoreGraphics 全局桌面上边界（逻辑点）。|Current Virtual Y Points, reported in pt.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_width_points|显示器|UNAVAILABLE|pt|桌面总宽度|Virtual Width Points|CoreGraphics 全局桌面总宽度（逻辑点）。|Current Virtual Width Points, reported in pt.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        display.virtual_height_points|显示器|UNAVAILABLE|pt|桌面总高度|Virtual Height Points|CoreGraphics 全局桌面总高度（逻辑点）。|Current Virtual Height Points, reported in pt.|0|左侧信息|Left info|普通应用只能看到当前逻辑显示器；多显示器虚拟桌面坐标不是公开 API。
        time.current|时间|TEXT||当前时间（24 小时）|Current|本地当前时间，格式 HH:mm:ss。|Current Current.||左侧主值|Primary value|纯本地计算，无需权限。
        time.current_12h|时间|TEXT||当前时间（12 小时）|Current 12h|本地当前时间，12 小时制并包含 AM/PM。|Current Current 12h.||左侧主值|Primary value|纯本地计算，无需权限。
        time.date|时间|TEXT||当前日期|Date|本地当前日期，格式 yyyy-MM-dd。|Current Date.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.datetime|时间|TEXT||当前日期时间|Datetime|本地当前日期时间。|Current Datetime.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.iso|时间|TEXT||ISO 日期时间|Iso|当前本地时间的 ISO 8601 表示。|Current Iso.||左侧信息|Left info|纯本地计算，无需权限。
        time.year|时间|NUMBER|年|年份|Year|当前年份。|Current Year, reported in years.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.month|时间|NUMBER|月|月份|Month|当前月份数字。|Current Month, reported in 月.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.month_name|时间|TEXT||月份名称|Month Name|当前文化区域下的月份名称。|Name reported for Month.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.day|时间|NUMBER|日|日期（日）|Day|当前月中的日期。|Current Day, reported in 日.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.day_of_week|时间|TEXT||星期（中文）|Day Of Week|当前星期的中文名称。|Current Day Of Week.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.day_of_week_en|时间|TEXT||星期（英文）|Day Of Week En|当前星期英文名称。|Current Day Of Week En.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.day_of_year|时间|NUMBER|天|一年中的第几天|Day Of Year|当前日期是一年中的第几天。|Current Day Of Year, reported in days.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.week_of_year|时间|NUMBER|周|周序号|Week Of Year|ISO 风格的当前周序号。|Current Week Of Year, reported in 周.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.hour|时间|NUMBER|时|小时|Hour|当前小时（0-23）。|Current Hour, reported in 时.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.minute|时间|NUMBER|分|分钟|Minute|当前分钟。|Current Minute, reported in 分.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.second|时间|NUMBER|秒|秒|Second|当前秒。|Current Second, reported in s.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.millisecond|时间|NUMBER|ms|毫秒|Millisecond|当前毫秒。|Current Millisecond, reported in ms.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.is_weekend|时间|BOOLEAN||是否周末|Is Weekend|当前日期是否为周六或周日。|Whether is weekend is active or true.||标题 / 条件|Title / Condition|纯本地计算，无需权限。
        time.unix_seconds|时间|NUMBER|秒|Unix 时间戳（秒）|Unix Seconds|当前 Unix 时间戳，单位秒。|Current Unix Seconds, reported in s.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.unix_milliseconds|时间|NUMBER|ms|Unix 时间戳（毫秒）|Unix Milliseconds|当前 Unix 时间戳，单位毫秒。|Current Unix Milliseconds, reported in ms.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.day.progress|时间|PROGRESS|%|当天进程|Day Progress|从当天 00:00:00 到次日 00:00:00 已经过的百分比。|Current Day Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.day.elapsed_seconds|时间|NUMBER|秒|当天已过时间|Day Elapsed Seconds|当天已经过的秒数。|Current Day Elapsed Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.day.remaining_seconds|时间|NUMBER|秒|当天剩余时间|Day Remaining Seconds|距离次日 00:00:00 的剩余秒数。|Current Day Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.week.progress|时间|PROGRESS|%|本周进程|Week Progress|从本周周一 00:00 到下周周一 00:00 的已过百分比。|Current Week Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.week.elapsed_seconds|时间|NUMBER|秒|本周已过时间|Week Elapsed Seconds|从本周周一开始已经过的秒数。|Current Week Elapsed Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.week.remaining_seconds|时间|NUMBER|秒|本周剩余时间|Week Remaining Seconds|距离下周周一的剩余秒数。|Current Week Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.month.progress|时间|PROGRESS|%|本月进程|Month Progress|从本月 1 日 00:00 到下月 1 日 00:00 的已过百分比。|Current Month Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.month.elapsed_seconds|时间|NUMBER|秒|本月已过时间|Month Elapsed Seconds|本月已经过的秒数。|Current Month Elapsed Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.month.remaining_seconds|时间|NUMBER|秒|本月剩余时间|Month Remaining Seconds|距离下月 1 日的剩余秒数。|Current Month Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.year.progress|时间|PROGRESS|%|本年进程|Year Progress|从今年 1 月 1 日到明年 1 月 1 日的已过百分比。|Current Year Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.year.elapsed_seconds|时间|NUMBER|秒|本年已过时间|Year Elapsed Seconds|今年已经过的秒数。|Current Year Elapsed Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.year.remaining_seconds|时间|NUMBER|秒|本年剩余时间|Year Remaining Seconds|距离明年 1 月 1 日的剩余秒数。|Current Year Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.display.progress|时间|PROGRESS|%|时间方案显示进度|Display Progress|时间方案用于圆环的进度；目标时间模式下为剩余比例。|Current Display Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.display.status_text|时间|TEXT||时间方案状态文字|Display Status Text|普通模式显示当天进程；目标时间模式显示“剩余??%”。|Current Display Status Text.||右侧状态|Right status|纯本地计算，无需权限。
        time.target.value|时间|TEXT||目标时间|Target Value|当前时间方案设置的每日目标时间。|Current Target Value.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.target.remaining_seconds|时间|NUMBER|秒|距离目标时间|Target Remaining Seconds|距离下一次每日目标时间的剩余秒数。|Current Target Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.target.remaining_percent|时间|PROGRESS|%|目标时间剩余比例|Target Remaining Percent|距离下一次目标时间的剩余时长占 24 小时的百分比。|Current Target Remaining Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.target.progress|时间|PROGRESS|%|目标时间已过比例|Target Progress|每日目标时间周期中已经过的比例。|Current Target Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.target.remaining_text|时间|TEXT||目标时间剩余文字|Target Remaining Text|按“剩余??%”生成的目标时间状态文字。|Current Target Remaining Text.||右侧状态|Right status|纯本地计算，无需权限。
        time.world.nyc|时间|TEXT||纽约时间|World Nyc|按 Windows Eastern Standard Time 实时换算。|Current World Nyc.||左侧主值|Primary value|使用 IANA 时区 ID 的纯本地计算。
        time.world.london|时间|TEXT||伦敦时间|World London|按 Windows GMT Standard Time 实时换算。|Current World London.||左侧主值|Primary value|使用 IANA 时区 ID 的纯本地计算。
        time.world.tokyo|时间|TEXT||东京时间|World Tokyo|按 Windows Tokyo Standard Time 实时换算。|Current World Tokyo.||左侧主值|Primary value|使用 IANA 时区 ID 的纯本地计算。
        time.world.beijing|时间|TEXT||北京时间|World Beijing|按 Windows China Standard Time 实时换算。|Current World Beijing.||左侧主值|Primary value|使用 IANA 时区 ID 的纯本地计算。
        time.day_progress|时间|PROGRESS|%|当天进程|Day Progress|从当天 00:00 到次日 00:00 的已过比例；桌面目录中的同义键是 time.day.progress。|Elapsed share of the current day; the desktop catalog spells this time.day.progress.|0;0.0|右侧状态 / 圆环|Right status / ring|纯本地计算，无需权限。
        time.target|时间|TEXT||每日目标时间|Daily Target|方案配置的每日目标时间 hh:mm:ss；桌面目录中的同义键是 time.target.value。|Configured daily target time (hh:mm:ss).||标题 / 左侧信息|Title / left info|纯本地计算，无需权限。
        time.timestamp|时间|NUMBER|秒|Unix 时间戳|Unix Timestamp|Unix 时间戳（秒）；桌面目录中的同义键是 time.unix_seconds。|Unix timestamp in seconds.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.weekday|时间|TEXT||星期|Weekday|当前星期名称；桌面目录中的同义键是 time.day_of_week / time.day_of_week_en。|Current weekday name.||标题 / 左侧信息|Title / left info|纯本地计算，无需权限。
        process.background.count|进程|UNAVAILABLE|个|后台进程数量|Background Count|当前没有主窗口句柄的进程数量。|Current Background Count.|0|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.top_cpu.name|进程|UNAVAILABLE||CPU 占用最高进程|Top CPU Name|最近一次采样中 CPU 使用率最高的进程名。|Name reported for Top CPU.||左侧主值|Primary value|Android 不允许普通应用枚举其它应用进程。
        process.top_cpu.pid|进程|UNAVAILABLE||CPU 最高进程 PID|Top CPU PID|CPU 使用率最高进程的 PID。|Current Top CPU PID.|0|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.top_cpu.usage|进程|UNAVAILABLE|%|最高进程 CPU 使用率|Top CPU Usage|按进程 TotalProcessorTime 差值计算的 CPU 使用率。|Current Top CPU Usage, reported in %.|0.0|右侧状态 / 圆环|Right status / Ring|Android 不允许普通应用枚举其它应用进程。
        process.top_memory.name|进程|UNAVAILABLE||内存占用最高进程|Top Memory Name|工作集最大的进程名。|Name reported for Top Memory.||左侧主值|Primary value|Android 不允许普通应用枚举其它应用进程。
        process.top_memory.pid|进程|UNAVAILABLE||内存最高进程 PID|Top Memory PID|工作集最大的进程 PID。|Current Top Memory PID.|0|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.top_memory.usage|进程|UNAVAILABLE|Byte|最高进程内存占用|Top Memory Usage|工作集最大的进程当前 Working Set。|Current Top Memory Usage, reported in Byte.|auto:1;mb:1|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.top_disk.name|进程|UNAVAILABLE||磁盘 I/O 最高进程|Top Disk Name|Windows PerfProc IODataBytesPersec 最大的进程名。|Name reported for Top Disk.||左侧主值|Primary value|Android 不允许普通应用枚举其它应用进程。
        process.top_disk.pid|进程|UNAVAILABLE||磁盘 I/O 最高进程 PID|Top Disk PID|磁盘 I/O 最高进程 PID。|Current Top Disk PID.|0|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.top_disk.usage|进程|UNAVAILABLE|Byte/s|最高进程磁盘 I/O|Top Disk Usage|该进程当前总 I/O 字节速率。|Current Top Disk Usage, reported in Byte/s.|auto:1;speed|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        process.gpu.top.name|进程|UNAVAILABLE||GPU 占用最高进程|GPU Top Name|GPU Engine 性能计数器中 GPU 使用率最高的进程名。|Name reported for GPU Top.||左侧主值|Primary value|Android 不允许普通应用枚举其它应用进程。
        process.gpu.top.usage|进程|UNAVAILABLE|%|最高进程 GPU 使用率|GPU Top Usage|GPU Engine 性能计数器汇总后的进程 GPU 使用率。|Current GPU Top Usage, reported in %.|0.0|右侧状态 / 圆环|Right status / Ring|Android 不允许普通应用枚举其它应用进程。
        process.count|进程|UNAVAILABLE|个|进程总数|Count|Linux 进程总数；Android 不允许枚举其它应用进程。|Current Count.|0|左侧信息|Left info|Android 不允许普通应用枚举其它应用进程。
        app.name|ECP 应用|TEXT||应用进程名称|Name|当前 Endfield Charge Plus 进程名称。|Name reported for Name.||标题 / 左侧信息|Title / Left info|由 Android 应用自身发布，而非系统指标。
        app.version|ECP 应用|TEXT||应用版本|Version|Endfield Charge Plus 产品版本。|Current Version.||标题 / 左侧信息|Title / Left info|由 Android 应用自身发布，而非系统指标。
        app.pid|ECP 应用|NUMBER||应用 PID|PID|当前应用进程 ID。|Current PID.|0|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.start_time|ECP 应用|TEXT||应用启动时间|Start Time|当前应用进程启动时间。|Current Start Time.||左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.uptime_seconds|ECP 应用|NUMBER|秒|应用运行时间|Uptime Seconds|当前应用进程已运行的秒数。|Current Uptime Seconds, reported in s.|duration|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.uptime_text|ECP 应用|TEXT||应用运行时间文字|Uptime Text|当前应用运行时间的可读文字。|Current Uptime Text.||左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.working_set_bytes|ECP 应用|NUMBER|Byte|应用工作集|Working Set Bytes|Endfield Charge Plus 当前工作集内存。|Current Working Set Bytes, reported in Byte.|mb:1;bytes|左侧主值|Primary value|由 Android 应用自身发布，而非系统指标。
        app.private_memory_bytes|ECP 应用|NUMBER|Byte|应用专用内存|Private Memory Bytes|当前应用进程专用内存。|Current Private Memory Bytes, reported in Byte.|mb:1;bytes|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.virtual_memory_bytes|ECP 应用|NUMBER|Byte|应用虚拟内存|Virtual Memory Bytes|当前应用进程虚拟内存大小。|Current Virtual Memory Bytes, reported in Byte.|mb:1;bytes|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.thread_count|ECP 应用|NUMBER|个|应用线程数|Thread Count|当前应用进程线程数量。|Current Thread Count.|0|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.handle_count|ECP 应用|UNAVAILABLE|个|应用句柄数|Handle Count|当前应用进程打开的 Windows 句柄数量。|Current Handle Count.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        app.cpu_time_seconds|ECP 应用|NUMBER|秒|应用累计 CPU 时间|CPU Time Seconds|当前应用进程累计消耗的处理器时间。|Current CPU Time Seconds, reported in s.|duration|左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.theme|ECP 应用|TEXT||应用主题|Theme|当前 Endfield Charge Plus 使用的主题。|Current Theme.||左侧信息|Left info|由 Android 应用自身发布，而非系统指标。
        app.preset_name|ECP 应用|TEXT||当前方案名称|Preset Name|当前选中的 HUD 方案显示名称。|Name reported for Preset.||标题 / 左侧信息|Title / Left info|由 Android 应用自身发布，而非系统指标。
        app.active_profile|ECP 应用|TEXT||当前方案 ID|Active Profile|当前 HUD 方案内部 ID。|Current Active Profile.||调试 / 条件|Debug / Condition|由 Android 应用自身发布，而非系统指标。
        app.gpu_usage|ECP 应用|UNAVAILABLE|%|本程序 GPU 使用率|GPU Usage|GPU Engine 性能计数器中当前 Endfield Charge Plus 进程的利用率。|Current GPU Usage, reported in %.|0.0|右侧状态 / 圆环|Right status / Ring|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        deepseek.balance|DeepSeek API|NUMBER|CNY|DeepSeek 总余额|Balance|DeepSeek API 账户人民币总余额。|Current Balance, reported in CNY.|0.00|左侧主值|Primary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.balance_text|DeepSeek API|TEXT||DeepSeek 总余额文字|Balance Text|已格式化为“¥0.00”的总余额文字。|Current Balance Text.||左侧主值|Primary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.granted_balance|DeepSeek API|NUMBER|CNY|DeepSeek 赠送余额|Granted Balance|DeepSeek API 账户赠送余额。|Current Granted Balance, reported in CNY.|0.00|左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.topped_up_balance|DeepSeek API|NUMBER|CNY|DeepSeek 充值余额|Topped Up Balance|DeepSeek API 账户充值余额。|Current Topped Up Balance, reported in CNY.|0.00|左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.available|DeepSeek API|BOOLEAN||DeepSeek API 可用状态|Available|余额接口返回的账户可用状态。|Whether available is active or true.||标题 / 条件|Title / Condition|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.available_text|DeepSeek API|TEXT||DeepSeek API 可用状态文字|Available Text|根据可用状态生成“可用 / 不可用”。|Current Available Text.||标题 / 右侧状态|Title / Right status|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.name|DeepSeek API|TEXT||当前时段（英文）|Period Name|当前为 PEAK 或 OFF-PEAK。|Name reported for Period.||标题|Title|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.name_zh|DeepSeek API|TEXT||当前时段（中文）|Period Name Zh|当前时段中文名称：高峰或低谷。|Current Period Name Zh.||标题|Title|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.is_peak|DeepSeek API|BOOLEAN||是否高峰|Period Is Peak|当前是否处于高峰时段。|Whether period is peak is active or true.||标题 / 条件|Title / Condition|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.is_off_peak|DeepSeek API|BOOLEAN||是否低谷|Period Is Off Peak|当前是否处于低谷时段。|Whether period is off peak is active or true.||标题 / 条件|Title / Condition|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.remaining_seconds|DeepSeek API|NUMBER|秒|距离时段切换|Period Remaining Seconds|距离下一次峰谷时段切换的剩余秒数。|Current Period Remaining Seconds, reported in s.|duration|左侧次值|Secondary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.remaining_text|DeepSeek API|TEXT||时段剩余文字|Period Remaining Text|“高峰时段剩余??:??:??”或“低谷时段剩余??:??:??”。|Current Period Remaining Text.||左侧次值|Secondary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.progress|DeepSeek API|PROGRESS|%|当前时段已过进度|Period Progress|当前峰谷时段已经经过的百分比。|Current Period Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.progress_text|DeepSeek API|TEXT||时段已过文字|Period Progress Text|“高峰已过??%”或“低谷已过??%”。|Current Period Progress Text.||右侧状态|Right status|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.next_switch_time|DeepSeek API|TEXT||下次切换时间（北京时间）|Period Next Switch Time|下一次高峰/低谷切换的北京时间，格式 HH:mm:ss。|Current Period Next Switch Time.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.next_switch_datetime|DeepSeek API|TEXT||下次切换日期时间（北京时间）|Period Next Switch Datetime|下一次峰谷切换的北京时间日期与时间，格式 yyyy-MM-dd HH:mm:ss。|Current Period Next Switch Datetime.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.next_switch_time_local|DeepSeek API|TEXT||下次切换时间（本地）|Period Next Switch Time Local|将下一次峰谷切换时刻转换为当前 Windows 用户时区后的本地时间，格式 HH:mm:ss；自动考虑当地夏令时。|Current Period Next Switch Time Local.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.next_switch_datetime_local|DeepSeek API|TEXT||下次切换日期时间（本地）|Period Next Switch Datetime Local|将下一次峰谷切换时刻转换为当前 Windows 用户时区后的本地日期与时间，格式 yyyy-MM-dd HH:mm:ss；自动考虑当地夏令时。|Current Period Next Switch Datetime Local.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.timezone|DeepSeek API|TEXT||DeepSeek 峰谷基准时区|Period Timezone|DeepSeek 峰谷规则使用的固定基准时区：北京时间 UTC+08:00。|Current Period Timezone.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.local_timezone|DeepSeek API|TEXT||用户本地时区|Period Local Timezone|当前 Windows 本地时区 ID，以及下一次切换时刻对应的 UTC 偏移；夏令时地区会按切换日期自动计算偏移。|Current Period Local Timezone.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.api.latency_ms|DeepSeek API|NUMBER|ms|DeepSeek API 延迟|API Latency Ms|最近一次官方 /user/balance 请求的 HTTP 往返耗时；余额请求 1 分钟缓存。|Measured API Latency Ms (ms).|0.0|右侧状态 / 圆环|Right status / Ring|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.currency|DeepSeek API|TEXT||余额货币|Balance Currency|余额货币代码，通常为 CNY；不虚构汇率。|Currency code of the balance, normally CNY.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.is_peak|DeepSeek API|BOOLEAN||是否高峰时段|Peak Period|当前是否处于 DeepSeek 高峰计费时段；桌面同义键是 deepseek.period.is_peak。|Whether the current time is a DeepSeek peak window.||标题 / 条件|Title / condition|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.status|DeepSeek API|TEXT||API 状态|API Status|余额查询状态文字；未配置 Key 或请求失败时给出明确原因。|Balance query status; a missing key or failed request yields an explicit status.||右侧状态|Right status|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.updated_at|DeepSeek API|TEXT||余额更新时间|Updated At|最近一次成功查询余额的本地时间。|Local time of the last successful balance query.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        security.defender.status|安全|UNAVAILABLE||Defender 状态|Defender Status|Windows Defender WMI 实时保护状态。|Current Defender Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.defender.last_scan|安全|UNAVAILABLE||Defender 最近扫描|Defender Last Scan|最近一次 Quick/Full Scan 完成时间。|Current Defender Last Scan.|time:relative|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.defender.threats|安全|UNAVAILABLE|项|Defender 当前威胁数|Defender Threats|MSFT_MpThreat 当前枚举到的威胁数量。|Current Defender Threats, reported in items.|0|右侧状态|Right status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.firewall.status|安全|UNAVAILABLE||防火墙状态|Firewall Status|Windows Firewall 当前活动配置是否启用。|Current Firewall Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.firewall.profile|安全|UNAVAILABLE||防火墙配置文件|Firewall Profile|当前活动防火墙配置文件：域/专用/公用。|Current Firewall Profile.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.bitlocker.status|安全|UNAVAILABLE||BitLocker 状态|Bitlocker Status|系统卷 BitLocker ProtectionStatus。|Current Bitlocker Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.bitlocker.encryption_percent|安全|UNAVAILABLE|%|BitLocker 加密进度|Bitlocker Encryption Percent|系统卷 EncryptionPercentage。|Current Bitlocker Encryption Percent, reported in %.|0|右侧状态 / 圆环|Right status / Ring|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.secure_boot|安全|UNAVAILABLE||Secure Boot|Secure Boot|UEFISecureBootEnabled 注册表状态。|Whether secure boot is active or true.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.tpm.present|安全|UNAVAILABLE||TPM 存在|TPM Present|Win32_Tpm 是否可枚举。|Whether tpm present is active or true.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.tpm.version|安全|UNAVAILABLE||TPM 版本|TPM Version|Win32_Tpm SpecVersion。|Current TPM Version.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.uac_status|安全|UNAVAILABLE||UAC 状态|UAC Status|EnableLUA 注册表状态。|Current UAC Status.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.smartscreen_status|安全|UNAVAILABLE||SmartScreen 状态|Smartscreen Status|Windows Explorer SmartScreenEnabled 配置。|Current Smartscreen Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.windows_update.status|安全|UNAVAILABLE||Windows Update 状态|Windows Update Status|综合待重启标志和可用更新搜索生成的状态。|Current Windows Update Status.||状态|Status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.windows_update.pending_count|安全|UNAVAILABLE|项|待安装更新数量|Windows Update Pending Count|Microsoft.Update.Session 搜索到的未安装且未隐藏更新数量。|Current Windows Update Pending Count.|0|右侧状态|Right status|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.vpn.active|安全|UNAVAILABLE||VPN 活动状态|VPN Active|与 network.vpn_status 同源的活动 VPN 检测。|Whether vpn active is active or true.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        security.proxy.enabled|安全|UNAVAILABLE||代理启用状态|Proxy Enabled|与 network.proxy_status 同源的系统代理状态。|Whether proxy enabled is active or true.||状态 / 条件|Status / Condition|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        usb.device.count|USB / 外设|UNAVAILABLE|个|USB 设备数量|Device Count|Win32_PnPEntity 中 USB PNP 设备数量。|Current Device Count.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        usb.device.list|USB / 外设|UNAVAILABLE||USB 设备列表|Device List|当前枚举到的 USB PNP 设备名称列表。|Current Device List.|sub:0:80|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        usb.storage.count|USB / 外设|UNAVAILABLE|个|可移动存储数量|Storage Count|当前已就绪 DriveType.Removable 卷数量。|Current Storage Count.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        usb.storage.list|USB / 外设|UNAVAILABLE||可移动存储列表|Storage List|当前可移动存储盘符列表。|Current Storage List.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        peripheral.mouse.name|USB / 外设|UNAVAILABLE||鼠标名称|Mouse Name|Win32_PointingDevice 报告的首个指针设备名称。|Name reported for Mouse.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        peripheral.keyboard.name|USB / 外设|UNAVAILABLE||键盘名称|Keyboard Name|Win32_Keyboard 报告的首个键盘设备名称。|Name reported for Keyboard.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        peripheral.gamepad.count|USB / 外设|UNAVAILABLE|个|XInput 手柄数量|Gamepad Count|XInput 0-3 控制器槽位中已连接的手柄数量。|Current Gamepad Count.|0|左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        peripheral.gamepad.name|USB / 外设|UNAVAILABLE||手柄名称|Gamepad Name|当前检测到的 XInput 控制器槽位名称。|Name reported for Gamepad.||左侧信息|Left info|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        peripheral.gamepad.battery|USB / 外设|UNAVAILABLE|%|手柄电量|Gamepad Battery|XInput 电池等级换算的近似百分比。|Current Gamepad Battery, reported in %.|0|右侧状态 / 圆环|Right status / Ring|Windows 专有指标：Android 没有对应概念（无 WMI/注册表/Windows 安全栈）。
        clipboard.has_text|剪贴板|BOOLEAN||剪贴板含文本|Has Text|当前 Windows 剪贴板是否包含 Unicode 文本。|Whether has text is active or true.||状态 / 条件|Status / Condition|剪贴板仅在应用拥有窗口焦点时可读（Android 10+），且需注意隐私。
        clipboard.text_length|剪贴板|NUMBER|字符|剪贴板文本长度|Text Length|当前剪贴板文本字符数。|Current Text Length, reported in 字符.|0|左侧信息|Left info|剪贴板仅在应用拥有窗口焦点时可读（Android 10+），且需注意隐私。
        clipboard.preview|剪贴板|TEXT||剪贴板文本预览|Preview|当前剪贴板文本去除多余空白后的前 80 个字符。|Current Preview.||左侧信息|Left info|剪贴板仅在应用拥有窗口焦点时可读（Android 10+），且需注意隐私。
        clipboard.has_image|剪贴板|UNAVAILABLE||剪贴板含图像|Has Image|当前剪贴板是否包含 Bitmap/DIB 图像格式。|Whether has image is active or true.||状态 / 条件|Status / Condition|Android 剪贴板面向普通应用只暴露文本；图片/文件计数不可用。
        clipboard.image_size|剪贴板|UNAVAILABLE||剪贴板图像尺寸|Image Size|DIB/DIBV5 图像可解析时显示宽×高。|Current Image Size.||左侧信息|Left info|Android 剪贴板面向普通应用只暴露文本；图片/文件计数不可用。
        clipboard.file_count|剪贴板|UNAVAILABLE|个|剪贴板文件数量|File Count|CF_HDROP 文件复制列表中的文件数量。|Current File Count.|0|左侧信息|Left info|Android 剪贴板面向普通应用只暴露文本；图片/文件计数不可用。
        clipboard.last_updated|剪贴板|TEXT||剪贴板最后变化时间|Last Updated|应用观察到 Clipboard Sequence Number 变化的本地时间。|Current Last Updated.|time:HH:mm:ss|左侧信息|Left info|剪贴板仅在应用拥有窗口焦点时可读（Android 10+），且需注意隐私。
        dev.docker.running|开发者工具|UNAVAILABLE||Docker 是否运行|Docker Running|检测 Docker Desktop / dockerd 后台进程。|Whether docker running is active or true.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.docker.containers|开发者工具|UNAVAILABLE|个|Docker 运行容器数|Docker Containers|docker ps -q 返回的运行中容器数量；Docker CLI 可用时提供。|Current Docker Containers, reported in count.|0|左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.docker.images|开发者工具|UNAVAILABLE|个|Docker 镜像数|Docker Images|docker images -q 的唯一镜像 ID 数量。|Current Docker Images, reported in count.|0|左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.wsl.running|开发者工具|UNAVAILABLE||WSL 是否运行|WSL Running|检测 wsl/wslhost/vmmemWSL 相关进程。|Whether wsl running is active or true.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.wsl.distro|开发者工具|UNAVAILABLE||WSL 发行版列表|WSL Distro|wsl -l -q 返回的发行版列表。|Current WSL Distro.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.wsl.memory_usage|开发者工具|UNAVAILABLE|Byte|WSL 内存占用|WSL Memory Usage|vmmemWSL/vmmem 工作集总量。|Current WSL Memory Usage, reported in Byte.|auto:1;gb:1|左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.git.branch|开发者工具|UNAVAILABLE||Git 当前分支|Git Branch|应用当前工作目录为 Git 仓库时返回分支。|Current Git Branch.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.git.status|开发者工具|UNAVAILABLE||Git 工作区状态|Git Status|当前工作目录 Git 状态，clean 或变更数量。|Current Git Status.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.git.last_commit|开发者工具|UNAVAILABLE||Git 最近提交|Git Last Commit|当前工作目录最近一次提交的短哈希和标题。|Current Git Last Commit.|sub:0:80|左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.node.version|开发者工具|UNAVAILABLE||Node.js 版本|Node Version|PATH 中 node --version 的结果。|Current Node Version.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.python.version|开发者工具|UNAVAILABLE||Python 版本|Python Version|PATH 中 python/py --version 的结果。|Current Python Version.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.java.version|开发者工具|UNAVAILABLE||Java 版本|Java Version|PATH 中 java -version 的首行结果。|Current Java Version.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.golang.version|开发者工具|UNAVAILABLE||Go 版本|Golang Version|PATH 中 go version 的结果。|Current Golang Version.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.rust.version|开发者工具|UNAVAILABLE||Rust 版本|Rust Version|PATH 中 rustc --version 的结果。|Current Rust Version.||左侧信息|Left info|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.vscode.running|开发者工具|UNAVAILABLE||VS Code 是否运行|Vscode Running|检测 Code / Code - Insiders 进程。|Whether vscode running is active or true.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.terminal.running|开发者工具|UNAVAILABLE||终端是否运行|Terminal Running|检测 Windows Terminal / PowerShell / cmd 进程。|Whether terminal running is active or true.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.ide.running|开发者工具|UNAVAILABLE||IDE 是否运行|Ide Running|检测 VS Code、Visual Studio、Rider、IntelliJ/PyCharm 等常见 IDE 进程。|Whether ide running is active or true.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        dev.llm.local_status|开发者工具|UNAVAILABLE||本地 LLM 状态|LLM Local Status|检测 Ollama、LM Studio、llama-server 等常见本地模型进程。|Current LLM Local Status.||状态|Status|桌面开发者工具指标（查询本机已安装工具/进程）；对 Android 应用无意义。
        custom.<source>.<field>|自定义数据|NUMBER||自定义字段|Custom Field|由 HTTP/JSON 数据源映射的自定义变量；实际含义由映射决定。|Custom value mapped from an HTTP / JSON data source.|0;0.0;gb:1;speed;duration|按数据含义决定|As appropriate for the value|由 HTTP/JSON 数据源映射；缺失字段发布状态文字而不是 0。
    """.trimIndent()

    /** Every descriptor, in ECP category display order (`CPU`, `GPU`, `内存`, `磁盘`, ...). */
    private val descriptors: List<VariableDescriptor> = parseTable(RAW_TABLE)

    private val byLowerName: Map<String, VariableDescriptor> = buildMap {
        for (descriptor in descriptors) put(descriptor.name.lowercase(), descriptor)
    }

    private val diskLetterDynamic: Map<String, VariableDescriptor> = descriptors
        .filter { it.name.startsWith("disk.<字母>.") }
        .associateBy { it.name.substringAfterLast('.') }

    private val diskMountDynamic: Map<String, VariableDescriptor> = descriptors
        .filter { it.name.startsWith("disk.mount_<hash>.") }
        .associateBy { it.name.substringAfterLast('.') }

    private val customDynamic: VariableDescriptor? =
        descriptors.firstOrNull { it.name == "custom.<source>.<field>" }

    private val diskLetterPattern = Regex("""^disk\.[a-z]\.(.+)$""")
    private val diskMountPattern = Regex("""^disk\.mount_[A-Za-z0-9_-]+\.(.+)$""")
    private val customPattern = Regex("""^(custom|http)\..+""")

    /** Every descriptor, in ECP category display order. */
    fun all(): List<VariableDescriptor> = descriptors

    /**
     * Looks a variable up by key.
     *
     * Legacy aliases are canonicalised first ([VariableAliases]), dynamic families
     * (`disk.c.usage`, `disk.mount_ab12cd.used_bytes`, `custom.myapi.temperature`) resolve to their
     * family descriptor with [VariableDescriptor.name] set to the queried key, and an unknown key
     * returns `null`.
     */
    fun byName(name: String): VariableDescriptor? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val canonical = VariableAliases.canonical(trimmed)
        byLowerName[canonical.lowercase()]?.let { return it }
        diskLetterPattern.matchEntire(canonical)?.let { match ->
            diskLetterDynamic[match.groupValues[1]]?.let { return it.copy(name = canonical) }
        }
        diskMountPattern.matchEntire(canonical)?.let { match ->
            diskMountDynamic[match.groupValues[1]]?.let { return it.copy(name = canonical) }
        }
        if (customPattern.matches(canonical)) return customDynamic?.copy(name = canonical)
        return null
    }

    /** The library grouped by [VariableDescriptor.categoryKey], keeping the display order. */
    fun byCategory(): Map<String, List<VariableDescriptor>> {
        val grouped = LinkedHashMap<String, MutableList<VariableDescriptor>>()
        for (descriptor in descriptors) {
            grouped.getOrPut(descriptor.categoryKey) { ArrayList() }.add(descriptor)
        }
        return grouped
    }

    /** The same list as [byCategory] but read-only; a convenience for the settings page. */
    fun byCategoryKey(categoryKey: String): List<VariableDescriptor> =
        descriptors.filter { it.categoryKey == categoryKey }

    /** Every category key in display order. */
    fun categories(): List<String> {
        val seen = LinkedHashSet<String>()
        for (descriptor in descriptors) seen.add(descriptor.categoryKey)
        return seen.toList()
    }

    /**
     * Case-insensitive substring search across key, Chinese/English label, Chinese/English
     * description, category, unit, type and the Android note. A blank query returns [all].
     */
    fun search(query: String): List<VariableDescriptor> {
        val needle = query.trim()
        if (needle.isEmpty()) return descriptors
        val lowered = needle.lowercase()
        return descriptors.filter { descriptor ->
            descriptor.name.lowercase().contains(lowered) ||
                descriptor.categoryKey.lowercase().contains(lowered) ||
                descriptor.labelZh.lowercase().contains(lowered) ||
                descriptor.labelEn.lowercase().contains(lowered) ||
                descriptor.descriptionZh.lowercase().contains(lowered) ||
                descriptor.descriptionEn.lowercase().contains(lowered) ||
                descriptor.unit.lowercase().contains(lowered) ||
                descriptor.androidNote.lowercase().contains(lowered) ||
                descriptor.type.name.lowercase().contains(lowered)
        }
    }

    /** All descriptors of one type, for example every [VariableType.UNAVAILABLE] entry. */
    fun ofType(type: VariableType): List<VariableDescriptor> = descriptors.filter { it.type == type }

    /** Number of variables in the library. */
    fun count(): Int = descriptors.size

    private fun parseTable(table: String): List<VariableDescriptor> = table.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { row ->
            val parts = row.split('|')
            check(parts.size == 12) { "VariableRegistry row must have 12 fields but had ${parts.size}: $row" }
            VariableDescriptor(
                name = parts[0],
                categoryKey = parts[1],
                type = VariableType.valueOf(parts[2]),
                unit = parts[3],
                descriptionZh = parts[6],
                descriptionEn = parts[7],
                commonFormats = parts[8].split(';').filter { it.isNotEmpty() },
                androidNote = parts[11],
                labelZh = parts[4],
                labelEn = parts[5],
                recommendedUseZh = parts[9],
                recommendedUseEn = parts[10],
            )
        }
}
