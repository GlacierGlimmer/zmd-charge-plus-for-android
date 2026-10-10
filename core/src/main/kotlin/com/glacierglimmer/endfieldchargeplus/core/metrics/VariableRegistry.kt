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
    val androidNoteEn: String = "",
) {
    /** True when the Android app can really read this variable. */
    val isSupportedOnAndroid: Boolean get() = type != VariableType.UNAVAILABLE

    /** The ECP template token, for example `{cpu.usage}`. */
    val templateToken: String get() = "{$name}"
}

/** Android-only catalog: every row is backed by a collector, including Root-capable nodes.
 * Desktop-only metadata and metrics without an implemented reader are deliberately absent.
 * Dynamic per-core and configured HTTP fields are resolved below, from real snapshot keys.
 */
object VariableRegistry {

    private val RAW_TABLE: String = """
        cpu.usage|CPU|PROGRESS|%|CPU 使用率|Usage|当前整机 CPU 总使用率。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|解析 /proc/stat（Android 上通常可读）；比率类指标必须两次采样后才发布。
        cpu.frequency_ghz|CPU|NUMBER|GHz|CPU 当前频率|Frequency GHz|Windows 性能数据报告的当前实时有效频率。|Current Frequency GHz, reported in GHz.|0.00|左侧主值|Primary value|多数机型可读 /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq，但 Android 10+ 部分机型禁读；先探测再发布。
        cpu.frequency_mhz|CPU|NUMBER|MHz|CPU 当前频率（MHz）|Frequency MHz|当前实时有效频率，单位 MHz。|Current Frequency MHz, reported in MHz.|0|左侧主值|Primary value|多数机型可读 /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq，但 Android 10+ 部分机型禁读；先探测再发布。
        cpu.max_frequency_ghz|CPU|NUMBER|GHz|CPU 最大频率|Max Frequency GHz|Win32_Processor 报告的最大时钟频率。|Current Max Frequency GHz, reported in GHz.|0.00|左侧次值|Secondary value|读取 /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq，通常可读；不可读时省略。
        cpu.abi|CPU|TEXT||CPU ABI|CPU ABI|Android 首选 ABI（Build.SUPPORTED_ABIS）；桌面目录中的同义键是 cpu.architecture。|Preferred Android ABI (Build.SUPPORTED_ABIS).||标题 / 左侧信息|Title / left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.cores|CPU|NUMBER|核|CPU 物理核心数|CPU Cores|物理核心数；桌面目录中的同义键是 cpu.physical_cores。|Physical core count; the desktop catalog spells this cpu.physical_cores.|0|左侧信息|Left info|读取 /sys/devices/system/cpu 与 sysfs cache 节点；个别机型受限，探测后发布。
        cpu.model|CPU|TEXT||CPU 型号|CPU Model|Android SoC 型号（Build.SOC_MODEL，API 31+）；桌面同义键是 cpu.name。|Android SoC model (Build.SOC_MODEL, API 31+).||标题 / 左侧信息|Title / left info|读取 /proc/cpuinfo，并优先使用 Build.SOC_MODEL / Build.SOC_MANUFACTURER（API 31+）。
        cpu.temperature_c|CPU|NUMBER|°C|CPU 温度|CPU Temperature|CPU 温度；Android 无公开 API（详见 androidNote）。|CPU temperature; Android exposes no public API.|0|左侧信息|Left info|Android 没有公开的 CPU 温度 API；/sys/class/thermal 与 hwmon 受 SELinux 限制且因机型而异。仅在真实读取成功时发布，否则省略该键而不是填 0。
        gpu.usage|GPU|PROGRESS|%|GPU 总使用率|Usage|当前所选 GPU 的实时利用率，取最忙 GPU 引擎。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        gpu.model|GPU|TEXT||GPU 型号|GPU Model|通过 EGL 读取的图形适配器名称；桌面同义键是 gpu.name。|Graphics adapter name read through EGL.||标题 / 左侧信息|Title / left info|通过 EGL（GL_RENDERER / GL_VENDOR）读取可验证的图形适配器身份，不推断显存或负载。
        gpu.temperature_c|GPU|NUMBER|°C|GPU 温度|GPU Temperature|GPU 温度；Android 无公开 API（详见 androidNote）。|GPU temperature; Android exposes no public API.|0|左侧信息|Left info|Android 无公开 GPU 温度 API；桌面端数值来自 LibreHardwareMonitor / nvidia-smi。
        gpu.frequency_ghz|GPU|NUMBER|GHz|GPU 频率|GPU Frequency|GPU 频率；Android 无公开 API，厂商节点不可移植。|GPU frequency; no public Android API exists.|0.00|左侧信息|Left info|Android 无公开 GPU 利用率 API；厂商节点（Qualcomm kgsl gpubusy、Mali 等）因厂商与 SELinux 而异，仅在真实读取成功时发布。
        memory.used_bytes|内存|NUMBER|Byte|已用物理内存|Used Bytes|当前已使用的物理内存。|Current Used Bytes, reported in Byte.|gb:1;gb:2;bytes|左侧主值|Primary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.available_bytes|内存|NUMBER|Byte|可用物理内存|Available Bytes|当前可用物理内存。|Current Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.total_bytes|内存|NUMBER|Byte|物理内存总量|Total Bytes|物理内存总容量。|Current Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.usage|内存|PROGRESS|%|内存使用率|Usage|已用物理内存占总物理内存的比例。|Current Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_total_bytes|内存|NUMBER|Byte|交换空间总量|Swap Total Bytes|交换空间总量。|Current Swap Total Bytes, reported in Byte.|0;gb:1|左侧次值|Secondary value|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.swap_used_bytes|内存|NUMBER|Byte|已用交换空间|Swap Used Bytes|已用交换空间。|Current Swap Used Bytes, reported in Byte.|0;gb:1|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.cached_bytes|内存|NUMBER|Byte|系统缓存内存|Cached Memory|Android 缓存/可回收内存；桌面目录中的同义键是 memory.cache_bytes。|Cached (reclaimable) memory; the desktop catalog spells this memory.cache_bytes.|gb:1;mb:0;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.free_bytes|内存|NUMBER|Byte|空闲物理内存|Free Memory|完全空闲的物理内存（MemFree）；桌面 macOS 版同名。|Completely free physical memory (MemFree).|gb:1;bytes|左侧信息|Left info|读取 /proc/meminfo（Android 上通常可读）；口径与桌面端保持一致，缺失时省略。
        memory.low|内存|BOOLEAN||内存紧张标记|Low Memory|ActivityManager.MemoryInfo.lowMemory：系统报告内存紧张。|ActivityManager.MemoryInfo.lowMemory.||标题 / 条件|Title / condition|优先使用 ActivityManager.MemoryInfo.totalMem/availMem，/proc/meminfo 作为补充。
        disk.system.used_bytes|磁盘|NUMBER|Byte|系统盘已用空间|System Used Bytes|Windows 系统盘已使用空间。|Current System Used Bytes, reported in Byte.|gb:1;bytes|左侧主值|Primary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.total_bytes|磁盘|NUMBER|Byte|系统盘总容量|System Total Bytes|Windows 系统盘总容量。|Current System Total Bytes, reported in Byte.|gb:1;bytes|左侧次值|Secondary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.usage|磁盘|PROGRESS|%|系统盘使用率|System Usage|系统盘已用空间占总容量的比例。|Current System Usage, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.free_percent|磁盘|PROGRESS|%|系统盘空闲率|System Free Percent|系统盘可用空间占总容量的比例。|Current System Free Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.system.available_bytes|磁盘|NUMBER|Byte|系统卷可用空间|System Available Bytes|系统卷普通用户可用空间（macOS 版定义）。|Current System Available Bytes, reported in Byte.|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.total_bytes|磁盘|NUMBER|Byte|数据分区总容量|Data Total|应用数据分区的总容量（StatFs）；桌面等价键是 disk.system.total_bytes。|Total size of the app data volume (StatFs).|gb:1;bytes|左侧次值|Secondary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.used_bytes|磁盘|NUMBER|Byte|数据分区已用空间|Data Used|应用数据分区的已用空间（StatFs）。|Used space of the app data volume (StatFs).|gb:1;bytes|左侧主值|Primary value|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.available_bytes|磁盘|NUMBER|Byte|数据分区可用空间|Data Available|应用数据分区的用户可用空间（StatFs.availableBytes）。|User-available space of the app data volume (StatFs.availableBytes).|gb:1;bytes|左侧信息|Left info|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        disk.data.usage|磁盘|PROGRESS|%|数据分区使用率|Data Usage|数据分区已用空间比例（1 - available/total，与 df 口径一致）。|Used share of the app data volume.|0;0.0|右侧状态 / 圆环|Right status / ring|通过 StatFs 读取数据卷/外置卷容量；不虚构块设备 I/O。
        battery.percent|电池|PROGRESS|%|电池电量|Percent|当前电池剩余电量百分比。|Current Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.remaining_mwh|电池|NUMBER|mWh|当前电池容量|Remaining mWh|当前剩余电池容量。|Current Remaining mWh, reported in mWh.|0|左侧主值|Primary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.full_mwh|电池|NUMBER|mWh|估计满充能量|Estimated full-charge energy|电池当前可充满的容量。|Current Full mWh, reported in mWh.|0|左侧次值|Secondary value|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.charging|电池|BOOLEAN||正在充电|Charging|当前是否正在充电。|Whether charging is active or true.||标题 / 条件|Title / Condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.voltage_mv|电池|NUMBER|mV|电池电压（mV）|Voltage Mv|Windows 电池接口报告的实时电压。不同机型可能不提供。|Current Voltage Mv, reported in mV.|0|左侧信息|Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.power_source|电池|TEXT||当前电源来源|Power Source|当前电源来源：“交流电源”或“电池”。|Current Power Source.||标题 / 左侧信息|Title / Left info|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.charge_counter|电池|NUMBER|µAh|充电计数器|Charge Counter|Android BatteryManager 的电荷计数器；不支持时返回 Integer.MIN_VALUE，按缺失处理。|Current Charge Counter, reported in µAh.|0;duration|左侧信息|Left info|BATTERY_PROPERTY_CHARGE_COUNTER（µAh）；不支持时返回 Integer.MIN_VALUE。
        battery.current_ma|电池|NUMBER|mA|电池电流|Battery Current|Android 电池瞬时电流；充电/放电方向按 EXTRA_STATUS 判定，不依赖符号。|Current Battery Current, reported in mA.|0;0.0|左侧信息|Left info|BATTERY_PROPERTY_CURRENT_NOW（µA）或 CURRENT_AVERAGE；充/放电方向按 EXTRA_STATUS 判定，不按符号。
        battery.health|电池|TEXT||电池健康状态码|Battery Health|Android EXTRA_HEALTH 状态码（GOOD/OVERHEAT/DEAD/...）；百分比健康度请用 battery.health_percent。|Android EXTRA_HEALTH status code (GOOD/OVERHEAT/DEAD/...).||标题 / 条件|Title / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.plugged|电池|TEXT||电源接入方式|Plugged|Android EXTRA_PLUGGED：AC / USB / WIRELESS / 未接入。|Android EXTRA_PLUGGED: AC / USB / WIRELESS / none.||标题 / 条件|Title / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.status|电池|NUMBER||电池状态码|Battery Status|Android EXTRA_STATUS 数值（CHARGING/DISCHARGING/FULL/NOT_CHARGING）。|Android EXTRA_STATUS code (CHARGING/DISCHARGING/FULL/NOT_CHARGING).|0|状态 / 条件|Status / condition|通过 BatteryManager / ACTION_BATTERY_CHANGED extras 读取；BATTERY_PROPERTY_* 在部分机型返回 Integer.MIN_VALUE，必须按缺失处理。
        battery.temperature_c|电池|NUMBER|°C|电池温度|Battery Temperature|Android 电池温度，摄氏；桌面目录中的同义键是 battery.temperature。|Battery temperature in °C; the desktop catalog spells this battery.temperature.|0;0.0|左侧信息|Left info|ACTION_BATTERY_CHANGED EXTRA_TEMPERATURE（0.1 °C）。
        network.download_bps|网络|NUMBER|Byte/s|系统总下载速度|Download B/s|所有已连接、非回环网络接口合计的实时下载速度。|Current Download B/s, reported in Byte/s.|speed|左侧主值|Primary value|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.upload_bps|网络|NUMBER|Byte/s|系统总上传速度|Upload B/s|所有已连接、非回环网络接口合计的实时上传速度。|Current Upload B/s, reported in Byte/s.|speed|左侧次值|Secondary value|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.display_download|网络|TEXT||方案下载速度文字|Display Download|按当前网络方案选择的单位生成的下载速度文字。|Current Display Download.||左侧主值|Primary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.display_upload|网络|TEXT||方案上传速度文字|Display Upload|按当前网络方案选择的单位生成的上传速度文字。|Current Display Upload.||左侧次值|Secondary value|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent|网络|PROGRESS|%|方案网络百分比|Profile Percent|按方案选择的百分比模式与“100% 对应速度”计算。|Current Profile Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.profile_percent_text|网络|TEXT||方案网络百分比文字|Profile Percent Text|根据模式生成“50% / ↓ 50% / ↑ 50%”等文字；较大值模式不显示箭头。|Current Profile Percent Text.||右侧状态|Right status|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.connected|网络|BOOLEAN||网络已连接|Network Connected|ConnectivityManager 报告的当前默认网络是否可用。|Whether the current default network is available.||标题 / 条件|Title / condition|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.type|网络|TEXT||网络类型|Network Type|当前传输类型：WIFI / CELLULAR / ETHERNET / VPN / NONE。|Current transport: WIFI / CELLULAR / ETHERNET / VPN / NONE.||标题 / 左侧信息|Title / left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.interface|网络|TEXT||网络接口名|Network Interface|当前默认网络的接口名（LinkProperties.interfaceName）。|Interface name of the default network (LinkProperties.interfaceName).||左侧信息|Left info|通过 ConnectivityManager / LinkProperties 读取当前活动网络的信息。
        network.download_total_bytes|网络|NUMBER|Byte|累计下载流量|Total Downloaded|TrafficStats 累计接收字节（开机以来）；桌面同义键是 network.total_received_bytes。|TrafficStats total received bytes since boot.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        network.upload_total_bytes|网络|NUMBER|Byte|累计上传流量|Total Uploaded|TrafficStats 累计发送字节（开机以来）；桌面同义键是 network.total_sent_bytes。|TrafficStats total sent bytes since boot.|bytes;gb:1|左侧信息|Left info|TrafficStats 总计（开机后单调递增）；计数器回绕或重启后重新起算，不发布负值或虚假速率。
        probe.target|网络探测|TEXT||探测地址|Target|当前网络包探测器使用的 IPv4、IPv6 或域名。|Current Target.||左侧信息 / 标题|Left info / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.protocol|网络探测|TEXT||检测协议|Protocol|当前使用的检测协议：ICMP、TCP 或 UDP。|Current Protocol.||左侧信息 / 标题|Left info / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.status_text|网络探测|TEXT||探测状态文字|Status Text|最近一次探测状态，例如“在线”“超时”“端口不可达”。|Current Status Text.||右侧状态 / 标题|Right status / Title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.latency_ms|网络探测|NUMBER|ms|探测延迟|Latency Ms|最近一次成功探测的往返/连接延迟；失败时按 999 ms 处理。|Measured Latency Ms (ms).|0;0.0|左侧主体信息|Left main info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.sent|网络探测|NUMBER|次|探测样本数|Sent|当前目标最近滚动统计窗口内的探测样本数量。|Current Sent, reported in count.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.received|网络探测|NUMBER|次|成功样本数|Received|当前目标最近滚动统计窗口内成功的探测次数。|Current Received, reported in count.|0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.loss_percent|网络探测|PROGRESS|%|丢包率|Loss Percent|当前地址、协议和端口组合最近 20 次探测的失败/超时比例。|Current Loss Percent, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.jitter_ms|网络探测|NUMBER|ms|延迟抖动|Jitter Ms|相邻成功样本延迟差的平均绝对值，用于近似表示抖动。|Current Jitter Ms, reported in ms.|0.0|左侧信息|Left info|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        probe.status|网络探测|TEXT||探测状态|Probe Status|最近一次探测状态；桌面目录中的同义键是 probe.status_text。|Latest probe status; the desktop catalog spells this probe.status_text.||右侧状态 / 标题|Right status / title|以 TCP connect 为主（INTERNET 权限）；ICMP 依赖 /system/bin/ping 且非公开 API，失败时发布状态文字而非 999。
        device.model|系统|TEXT||设备型号|Device Model|Build.MODEL 设备型号。|Build.MODEL device model.||标题 / 左侧信息|Title / left info|Android Build / PowerManager / Settings 只读系统属性。
        device.manufacturer|系统|TEXT||设备制造商|Device Manufacturer|Build.MANUFACTURER 制造商。|Build.MANUFACTURER.||标题 / 左侧信息|Title / left info|Android Build / PowerManager / Settings 只读系统属性。
        device.android_version|系统|TEXT||Android 版本|Android Version|Build.VERSION.RELEASE。|Build.VERSION.RELEASE.||左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.sdk|系统|NUMBER||API 级别|API Level|Build.VERSION.SDK_INT。|Build.VERSION.SDK_INT.|0|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.uptime_seconds|系统|NUMBER|秒|开机时长|Device Uptime|SystemClock.elapsedRealtime()/1000（不含深度睡眠）。|SystemClock.elapsedRealtime()/1000.|duration|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        device.screen_state|系统|TEXT||屏幕状态|Screen State|PowerManager.isInteractive / KEYGUARD：ON / OFF / LOCKED。|Screen state: ON / OFF / LOCKED.||标题 / 条件|Title / condition|Android Build / PowerManager / Settings 只读系统属性。
        device.brightness|系统|NUMBER||屏幕亮度|Screen Brightness|Settings.System.SCREEN_BRIGHTNESS（0-255）；不假设百分比。|Settings.System.SCREEN_BRIGHTNESS (0-255).|0|左侧信息|Left info|Android Build / PowerManager / Settings 只读系统属性。
        time.current|时间|TEXT||当前时间（24 小时）|Current|本地当前时间，格式 HH:mm:ss。|Current Current.||左侧主值|Primary value|纯本地计算，无需权限。
        time.date|时间|TEXT||当前日期|Date|本地当前日期，格式 yyyy-MM-dd。|Current Date.||标题 / 左侧信息|Title / Left info|纯本地计算，无需权限。
        time.display.progress|时间|PROGRESS|%|时间方案显示进度|Display Progress|时间方案用于圆环的进度；目标时间模式下为剩余比例。|Current Display Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.display.status_text|时间|TEXT||时间方案状态文字|Display Status Text|普通模式显示当天进程；目标时间模式显示“剩余??%”。|Current Display Status Text.||右侧状态|Right status|纯本地计算，无需权限。
        time.target.remaining_seconds|时间|NUMBER|秒|距离目标时间|Target Remaining Seconds|距离下一次每日目标时间的剩余秒数。|Current Target Remaining Seconds, reported in s.|duration|左侧信息|Left info|纯本地计算，无需权限。
        time.target.progress|时间|PROGRESS|%|目标时间已过比例|Target Progress|每日目标时间周期中已经过的比例。|Current Target Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|纯本地计算，无需权限。
        time.target.remaining_text|时间|TEXT||目标时间剩余文字|Target Remaining Text|按“剩余??%”生成的目标时间状态文字。|Current Target Remaining Text.||右侧状态|Right status|纯本地计算，无需权限。
        time.day_progress|时间|PROGRESS|%|当天进程|Day Progress|从当天 00:00 到次日 00:00 的已过比例；桌面目录中的同义键是 time.day.progress。|Elapsed share of the current day; the desktop catalog spells this time.day.progress.|0;0.0|右侧状态 / 圆环|Right status / ring|纯本地计算，无需权限。
        time.target|时间|TEXT||每日目标时间|Daily Target|方案配置的每日目标时间 hh:mm:ss；桌面目录中的同义键是 time.target.value。|Configured daily target time (hh:mm:ss).||标题 / 左侧信息|Title / left info|纯本地计算，无需权限。
        time.timestamp|时间|NUMBER|秒|Unix 时间戳|Unix Timestamp|Unix 时间戳（秒）；桌面目录中的同义键是 time.unix_seconds。|Unix timestamp in seconds.|0|左侧信息|Left info|纯本地计算，无需权限。
        time.weekday|时间|TEXT||星期|Weekday|当前星期名称；桌面目录中的同义键是 time.day_of_week / time.day_of_week_en。|Current weekday name.||标题 / 左侧信息|Title / left info|纯本地计算，无需权限。
        deepseek.balance|DeepSeek API|NUMBER|CNY|DeepSeek 总余额|Balance|DeepSeek API 账户人民币总余额。|Current Balance, reported in CNY.|0.00|左侧主值|Primary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.name|DeepSeek API|TEXT||当前时段（英文）|Period Name|当前为 PEAK 或 OFF-PEAK。|Name reported for Period.||标题|Title|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.name_zh|DeepSeek API|TEXT||当前时段（中文）|Period Name Zh|当前时段中文名称：高峰或低谷。|Current Period Name Zh.||标题|Title|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.remaining_text|DeepSeek API|TEXT||时段剩余文字|Period Remaining Text|“高峰时段剩余??:??:??”或“低谷时段剩余??:??:??”。|Current Period Remaining Text.||左侧次值|Secondary value|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.progress|DeepSeek API|PROGRESS|%|当前时段已过进度|Period Progress|当前峰谷时段已经经过的百分比。|Current Period Progress, reported in %.|0;0.0|右侧状态 / 圆环|Right status / Ring|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.period.progress_text|DeepSeek API|TEXT||时段已过文字|Period Progress Text|“高峰已过??%”或“低谷已过??%”。|Current Period Progress Text.||右侧状态|Right status|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.currency|DeepSeek API|TEXT||余额货币|Balance Currency|余额货币代码，通常为 CNY；不虚构汇率。|Currency code of the balance, normally CNY.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.is_peak|DeepSeek API|BOOLEAN||是否高峰时段|Peak Period|当前是否处于 DeepSeek 高峰计费时段；桌面同义键是 deepseek.period.is_peak。|Whether the current time is a DeepSeek peak window.||标题 / 条件|Title / condition|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.status|DeepSeek API|TEXT||API 状态|API Status|余额查询状态文字；未配置 Key 或请求失败时给出明确原因。|Balance query status; a missing key or failed request yields an explicit status.||右侧状态|Right status|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        deepseek.updated_at|DeepSeek API|TEXT||余额更新时间|Updated At|最近一次成功查询余额的本地时间。|Local time of the last successful balance query.||左侧信息|Left info|HTTPS + 1 分钟缓存；未配置 API Key 或请求失败时发布明确状态文字，不返回伪造余额。
        disk.data.free_percent|磁盘|PROGRESS|%|分区可用比例|Available share|通过 StatFs 读取可用空间并除以总容量。|Available bytes divided by total capacity from StatFs.|0.0|右侧状态|Right status|StatFs
    """.trimIndent()

    /** Every descriptor, in ECP category display order (`CPU`, `GPU`, `内存`, `磁盘`, ...). */
    private val descriptors: List<VariableDescriptor> = parseTable(RAW_TABLE).map(AndroidVariableDescriptions::adapt)

    private val byLowerName: Map<String, VariableDescriptor> = buildMap {
        for (descriptor in descriptors) put(descriptor.name.lowercase(), descriptor)
    }

    private val customDynamic = VariableDescriptor("custom.<source>.<field>", "自定义数据", VariableType.NUMBER,
        unit = "", commonFormats = listOf("0.0"),
        descriptionZh = "已配置 HTTP/JSON 数据源的真实字段映射。", descriptionEn = "A real mapped field from the configured HTTP/JSON source.",
        labelZh = "HTTP 字段", labelEn = "HTTP field", androidNote = "HTTP/JSON 字段映射", androidNoteEn = "HTTP/JSON field mapping")
    private val corePattern = Regex("^cpu\\.core[0-9]+\\.usage$")
    private val customPattern = Regex("^(custom|http)\\.[A-Za-z0-9_]+\\.[A-Za-z0-9_.]+$")

    /** Every descriptor, in ECP category display order. */
    fun all(): List<VariableDescriptor> = descriptors

    /**
     * Looks a variable up by key.
     *
     * Legacy aliases are canonicalised first ([VariableAliases]), dynamic families
     * (`cpu.core0.usage`, `custom.myapi.temperature`) resolve to their
     * family descriptor with [VariableDescriptor.name] set to the queried key, and an unknown key
     * returns `null`.
     */
    fun byName(name: String): VariableDescriptor? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val canonical = VariableAliases.canonical(trimmed)
        byLowerName[canonical.lowercase()]?.let { return it }
        if (corePattern.matches(canonical)) return byLowerName.getValue("cpu.usage").copy(
            name = canonical, labelZh = "CPU 逐核使用率", labelEn = "Per-core CPU usage")
        if (customPattern.matches(canonical)) return customDynamic.copy(name = canonical)
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
