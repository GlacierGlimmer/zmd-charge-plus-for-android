package com.glacierglimmer.endfieldchargeplus.core.i18n

import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile

/**
 * The English overlay for the built-in HUD schemes and for the variable-library categories.
 *
 * This is a faithful port of `Customization/BuiltInProfileLocalization.cs`: only profiles that
 * carry a [HudProfile.builtInKey] are localized, and only for [UiLanguage.EN]. The overlay changes
 * the category, the display name, the title template and — for the packet probe — the right
 * template, exactly like the desktop edition (`DeepSeek {deepseek.period.name}` and
 * `Loss {probe.loss_percent|0}`). Everything else (tagline, primary, secondary, progress, icons,
 * colours) is language independent.
 *
 * [categoryName] ports the category half of `VariableLocalization.Localize` (its `CategoryMap`
 * plus the `HumanizeWords` fallback), so the same call translates built-in profile categories and
 * [com.glacierglimmer.endfieldchargeplus.core.metrics.VariableRegistry] category keys.
 */
object BuiltInProfileLocalization {

    /** `VariableLocalization.CategoryMap`, the authoritative ECP category wording. */
    private val CATEGORY_MAP: Map<String, String> = mapOf(
        "电池" to "Battery",
        "内存" to "Memory",
        "磁盘" to "Disk",
        "系统" to "System",
        "网络" to "Network",
        "网络探测" to "Network Probe",
        "网络包探测器" to "Packet Probe",
        "Ping" to "Ping",
        "Ping（兼容）" to "Ping (Compatibility)",
        "时间" to "Time",
        "进程" to "Processes",
        "应用进程" to "App Process",
        "ECP 应用" to "ECP App",
        "显示器" to "Display",
        "剪贴板" to "Clipboard",
        "USB / 外设" to "USB / Devices",
        "开发者工具" to "Developer",
        "安全" to "Security",
        "自定义数据" to "Custom Data",
        "CPU" to "CPU",
        "GPU" to "GPU",
        "DeepSeek API" to "DeepSeek API",
    )

    /** `VariableLocalization.TokenMap`, used only by the humanizing fallback. */
    private val TOKEN_MAP: Map<String, String> = mapOf(
        "cpu" to "CPU", "gpu" to "GPU", "vram" to "VRAM", "ram" to "RAM",
        "api" to "API", "dns" to "DNS", "tcp" to "TCP", "udp" to "UDP",
        "icmp" to "ICMP", "vpn" to "VPN", "wifi" to "Wi-Fi", "wlan" to "WLAN",
        "ipv4" to "IPv4", "ipv6" to "IPv6", "ip" to "IP", "mac" to "MAC",
        "ssid" to "SSID", "bssid" to "BSSID", "wsl" to "WSL", "tpm" to "TPM",
        "uac" to "UAC", "bios" to "BIOS", "smbios" to "SMBIOS", "pcie" to "PCIe",
        "pid" to "PID", "llm" to "LLM", "fps" to "FPS", "dpc" to "DPC",
        "io" to "I/O", "url" to "URL", "json" to "JSON", "http" to "HTTP",
        "https" to "HTTPS", "mhz" to "MHz", "ghz" to "GHz", "mwh" to "mWh",
        "wh" to "Wh", "kb" to "KB", "mb" to "MB", "gb" to "GB", "bps" to "B/s",
        "dbm" to "dBm", "rpm" to "RPM", "id" to "ID", "os" to "OS",
        "ac" to "AC", "ui" to "UI", "usb" to "USB", "dll" to "DLL",
        "exe" to "EXE", "msix" to "MSIX", "utc" to "UTC", "cny" to "CNY",
    )

    /**
     * Returns the profile as the given language should render it.
     *
     * A profile without a [HudProfile.builtInKey] and every [UiLanguage.ZH_CN] request return the
     * profile unchanged; an unknown built-in key is returned unchanged as well (`_ => profile`).
     */
    fun forLanguage(profile: HudProfile, language: UiLanguage): HudProfile {
        if (language != UiLanguage.EN || profile.builtInKey.isBlank()) return profile
        return when (profile.builtInKey.lowercase()) {
            "system.battery" -> profile.copy(category = "System", name = "Battery", titleTemplate = "Battery")
            "system.cpu" -> profile.copy(category = "System", name = "CPU", titleTemplate = "CPU")
            "system.memory" -> profile.copy(category = "System", name = "Memory", titleTemplate = "Memory")
            "system.gpu" -> profile.copy(category = "System", name = "GPU", titleTemplate = "GPU")
            "system.network" -> profile.copy(category = "System", name = "Network", titleTemplate = "Network")
            "system.disk" -> profile.copy(category = "System", name = "System Disk", titleTemplate = "System Disk")
            "time.day-progress" -> profile.copy(category = "Time", name = "Day Progress", titleTemplate = "Day Progress")
            "deepseek.balance-period" -> profile.copy(
                category = "DeepSeek API",
                name = "Balance / Period",
                titleTemplate = "DeepSeek {deepseek.period.name}",
            )
            "network.ping" -> profile.copy(
                category = "Network",
                name = "Packet Probe",
                titleTemplate = "Packet Probe",
                rightTemplate = "Loss {probe.loss_percent|0}",
            )
            else -> profile
        }
    }

    /**
     * `BuiltInProfileLocalization.DisplayName`: `"{Category} - {Name}"` for a built-in (in the
     * requested language), the stored name for a custom profile, or `自定义方案` / `Custom Profile`
     * when a custom profile has no name at all.
     */
    fun displayName(profile: HudProfile, language: UiLanguage): String {
        if (profile.isBuiltIn || profile.builtInKey.isNotBlank()) {
            val localized = forLanguage(profile, language)
            return "${localized.category} - ${localized.name}"
        }
        return if (profile.name.isBlank()) {
            if (language.isEnglish) "Custom Profile" else "自定义方案"
        } else {
            profile.name
        }
    }

    /**
     * The display name of a variable category (or of a profile category).
     *
     * [UiLanguage.ZH_CN] returns the ECP Chinese category unchanged; [UiLanguage.EN] uses
     * `VariableLocalization.CategoryMap` and falls back to the desktop `HumanizeWords` algorithm
     * for a category that is not in the map.
     */
    fun categoryName(category: String, language: UiLanguage): String {
        if (language != UiLanguage.EN) return category
        CATEGORY_MAP[category]?.let { return it }
        return humanizeWords(category)
    }

    /** `VariableLocalization.HumanizeWords`: `_`/`-` become spaces, known tokens are uppercased. */
    private fun humanizeWords(input: String): String {
        if (input.isBlank()) return input
        val clean = input.replace(Regex("[_\\-]+"), " ").trim()
        return clean.split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                TOKEN_MAP[word.lowercase()] ?: if (word.length == 1) word.uppercase() else word[0].uppercase() + word.substring(1)
            }
    }
}
