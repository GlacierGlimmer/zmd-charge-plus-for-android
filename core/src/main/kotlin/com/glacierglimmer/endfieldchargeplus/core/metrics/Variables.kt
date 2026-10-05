package com.glacierglimmer.endfieldchargeplus.core.metrics

/**
 * Canonical variable names.
 *
 * These strings are the cross-platform contract: the same name must express the same concept on
 * Windows, Linux, macOS and Android. Names that come from the desktop editions keep their exact
 * spelling (`memory.usage`, `battery.remaining_mwh`, `probe.latency_ms`, ...).
 *
 * APPEND-ONLY: never rename or remove a constant; add new ones and (when a desktop name changed)
 * register an alias in [VariableAliases].
 */
object Variables {

    // ---- time -------------------------------------------------------------------------------
    const val TIME_CURRENT = "time.current"
    const val TIME_DATE = "time.date"
    const val TIME_WEEKDAY = "time.weekday"
    const val TIME_TIMESTAMP = "time.timestamp"
    const val TIME_DAY_PROGRESS = "time.day_progress"
    const val TIME_DISPLAY_PROGRESS = "time.display.progress"
    const val TIME_DISPLAY_STATUS_TEXT = "time.display.status_text"
    const val TIME_TARGET = "time.target"
    const val TIME_TARGET_REMAINING_SECONDS = "time.target.remaining_seconds"
    const val TIME_TARGET_REMAINING_TEXT = "time.target.remaining_text"
    const val TIME_TARGET_PROGRESS = "time.target.progress"

    // ---- memory -----------------------------------------------------------------------------
    const val MEMORY_TOTAL_BYTES = "memory.total_bytes"
    const val MEMORY_USED_BYTES = "memory.used_bytes"
    const val MEMORY_AVAILABLE_BYTES = "memory.available_bytes"
    const val MEMORY_FREE_BYTES = "memory.free_bytes"
    const val MEMORY_CACHED_BYTES = "memory.cached_bytes"
    const val MEMORY_SWAP_TOTAL_BYTES = "memory.swap_total_bytes"
    const val MEMORY_SWAP_USED_BYTES = "memory.swap_used_bytes"
    const val MEMORY_USAGE = "memory.usage"
    const val MEMORY_LOW = "memory.low"

    // ---- CPU --------------------------------------------------------------------------------
    const val CPU_USAGE = "cpu.usage"
    const val CPU_FREQUENCY_GHZ = "cpu.frequency_ghz"
    const val CPU_FREQUENCY_MHZ = "cpu.frequency_mhz"
    const val CPU_MAX_FREQUENCY_GHZ = "cpu.max_frequency_ghz"
    const val CPU_TEMPERATURE_C = "cpu.temperature_c"
    const val CPU_CORES = "cpu.cores"
    const val CPU_MODEL = "cpu.model"
    const val CPU_ABI = "cpu.abi"
    const val CPU_PER_CORE_PREFIX = "cpu.core"

    // ---- GPU --------------------------------------------------------------------------------
    const val GPU_USAGE = "gpu.usage"
    const val GPU_MEMORY_USED_BYTES = "gpu.memory_used_bytes"
    const val GPU_MEMORY_TOTAL_BYTES = "gpu.memory_total_bytes"
    const val GPU_TEMPERATURE_C = "gpu.temperature_c"
    const val GPU_FREQUENCY_GHZ = "gpu.frequency_ghz"
    const val GPU_MODEL = "gpu.model"

    // ---- battery ----------------------------------------------------------------------------
    const val BATTERY_PERCENT = "battery.percent"
    const val BATTERY_REMAINING_MWH = "battery.remaining_mwh"
    const val BATTERY_FULL_MWH = "battery.full_mwh"
    const val BATTERY_TEMPERATURE_C = "battery.temperature_c"
    const val BATTERY_VOLTAGE_MV = "battery.voltage_mv"
    const val BATTERY_CURRENT_MA = "battery.current_ma"
    const val BATTERY_CHARGING = "battery.charging"
    const val BATTERY_PLUGGED = "battery.plugged"
    const val BATTERY_POWER_SOURCE = "battery.power_source"
    const val BATTERY_STATUS = "battery.status"
    const val BATTERY_HEALTH = "battery.health"
    const val BATTERY_CHARGE_COUNTER = "battery.charge_counter"

    // ---- network traffic --------------------------------------------------------------------
    const val NETWORK_DOWNLOAD_BPS = "network.download_bps"
    const val NETWORK_UPLOAD_BPS = "network.upload_bps"
    const val NETWORK_DOWNLOAD_TOTAL_BYTES = "network.download_total_bytes"
    const val NETWORK_UPLOAD_TOTAL_BYTES = "network.upload_total_bytes"
    const val NETWORK_DISPLAY_DOWNLOAD = "network.display_download"
    const val NETWORK_DISPLAY_UPLOAD = "network.display_upload"
    const val NETWORK_PROFILE_PERCENT = "network.profile_percent"
    const val NETWORK_PROFILE_PERCENT_TEXT = "network.profile_percent_text"
    const val NETWORK_TYPE = "network.type"
    const val NETWORK_CONNECTED = "network.connected"
    const val NETWORK_INTERFACE = "network.interface"
    const val NETWORK_WIFI_SSID = "network.wifi_ssid"

    // ---- storage ----------------------------------------------------------------------------
    const val DISK_SYSTEM_TOTAL_BYTES = "disk.system.total_bytes"
    const val DISK_SYSTEM_USED_BYTES = "disk.system.used_bytes"
    const val DISK_SYSTEM_AVAILABLE_BYTES = "disk.system.available_bytes"
    const val DISK_SYSTEM_USAGE = "disk.system.usage"
    const val DISK_DATA_TOTAL_BYTES = "disk.data.total_bytes"
    const val DISK_DATA_USED_BYTES = "disk.data.used_bytes"
    const val DISK_DATA_AVAILABLE_BYTES = "disk.data.available_bytes"
    const val DISK_DATA_USAGE = "disk.data.usage"

    // ---- network packet probe ---------------------------------------------------------------
    const val PROBE_LATENCY_MS = "probe.latency_ms"
    const val PROBE_LOSS_PERCENT = "probe.loss_percent"
    const val PROBE_SENT = "probe.sent"
    const val PROBE_RECEIVED = "probe.received"
    const val PROBE_JITTER_MS = "probe.jitter_ms"
    const val PROBE_TARGET = "probe.target"
    const val PROBE_PROTOCOL = "probe.protocol"
    const val PROBE_STATUS = "probe.status"

    /**
     * Localized short status shown instead of a latency value when the probe did not succeed.
     * The Linux/macOS editions substitute a status string for `probe.latency_ms` on failure
     * (never the Windows `999` placeholder); Android keeps the numeric variable unavailable and
     * exposes the reason through this variable instead, so a template can read
     * `{probe.latency_ms|0}ms` on success and `{probe.status_text}` on failure without ever
     * showing a fabricated number.
     */
    const val PROBE_STATUS_TEXT = "probe.status_text"

    // ---- DeepSeek API -----------------------------------------------------------------------
    const val DEEPSEEK_BALANCE = "deepseek.balance"
    const val DEEPSEEK_CURRENCY = "deepseek.currency"
    const val DEEPSEEK_STATUS = "deepseek.status"
    const val DEEPSEEK_IS_PEAK = "deepseek.is_peak"
    const val DEEPSEEK_PERIOD_PROGRESS = "deepseek.period.progress"
    const val DEEPSEEK_PERIOD_NAME = "deepseek.period.name"
    const val DEEPSEEK_PERIOD_NAME_ZH = "deepseek.period.name_zh"
    const val DEEPSEEK_PERIOD_REMAINING_TEXT = "deepseek.period.remaining_text"
    const val DEEPSEEK_PERIOD_PROGRESS_TEXT = "deepseek.period.progress_text"
    const val DEEPSEEK_UPDATED_AT = "deepseek.updated_at"

    // ---- device -----------------------------------------------------------------------------
    const val DEVICE_MODEL = "device.model"
    const val DEVICE_MANUFACTURER = "device.manufacturer"
    const val DEVICE_ANDROID_VERSION = "device.android_version"
    const val DEVICE_SDK = "device.sdk"
    const val DEVICE_UPTIME_SECONDS = "device.uptime_seconds"
    const val DEVICE_SCREEN_STATE = "device.screen_state"
    const val DEVICE_BRIGHTNESS = "device.brightness"

    /**
     * Prefix for user-defined HTTP/JSON fields. The desktop editions expose them as
     * `custom.<source>.<field>`; `http.` is accepted as an alias during import.
     */
    const val HTTP_PREFIX = "custom."
    const val HTTP_LEGACY_PREFIX = "http."

    fun httpVariable(source: String, field: String): String =
        HTTP_PREFIX + sanitizeSegment(source) + "." + sanitizeSegment(field)

    private fun sanitizeSegment(value: String): String =
        value.trim().lowercase().replace(' ', '_').replace('-', '_')

    /** Every probe variable, including the legacy `ping.*` mirror names. */
    val PROBE_ALL: List<String> = listOf(
        PROBE_LATENCY_MS, PROBE_LOSS_PERCENT, PROBE_SENT, PROBE_RECEIVED,
        PROBE_JITTER_MS, PROBE_TARGET, PROBE_PROTOCOL, PROBE_STATUS, PROBE_STATUS_TEXT,
    )
}

/**
 * Legacy names that must keep working. The desktop editions publish `ping.*` in parallel with
 * `probe.*` for a while, so Android resolves both spellings to the same value.
 */
object VariableAliases {
    private val aliases: Map<String, String> = mapOf(
        "ping.latency_ms" to Variables.PROBE_LATENCY_MS,
        "ping.loss_percent" to Variables.PROBE_LOSS_PERCENT,
        "ping.sent" to Variables.PROBE_SENT,
        "ping.received" to Variables.PROBE_RECEIVED,
        "ping.jitter_ms" to Variables.PROBE_JITTER_MS,
        "ping.target" to Variables.PROBE_TARGET,
        "ping.status" to Variables.PROBE_STATUS,
        // Documented ECP spelling variants observed in desktop documentation.
        "memory.usage_percent" to Variables.MEMORY_USAGE,
        "cpu.usage_percent" to Variables.CPU_USAGE,
        "battery.level" to Variables.BATTERY_PERCENT,
        "disk.system.free_bytes" to Variables.DISK_SYSTEM_AVAILABLE_BYTES,
        "disk.system.free_percent" to "disk.system.free_percent",
    )

    /** Canonical name for [name]; returns [name] unchanged when it is not an alias. */
    fun canonical(name: String): String = aliases[name] ?: name

    fun all(): Map<String, String> = aliases
}
