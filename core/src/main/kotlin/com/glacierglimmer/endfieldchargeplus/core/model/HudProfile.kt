package com.glacierglimmer.endfieldchargeplus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** A colour rule: when `variable` compares true against `value`, the accent colour becomes `color`. */
@Serializable
data class HudColorRule(
    @SerialName("Variable") val variable: String = "",
    @SerialName("Operator") val operator: String = ">=",
    @SerialName("Value") val value: Double = 0.0,
    @SerialName("Color") val color: String = "#C6CA4C",
)

/**
 * One HUD scheme ("方案"). Field names, defaults and semantics mirror
 * `EndfieldChargePlus.Customization.HudProfile` from the Windows edition so that exported
 * configurations remain interchangeable.
 */
@Serializable
data class HudProfile(
    @SerialName("Id") val id: String = newId(),

    /** Built-in schemes ship with the application and are read-only. */
    @SerialName("IsBuiltIn") val isBuiltIn: Boolean = false,

    /** Stable identity of a built-in scheme, for example `system.memory`. */
    @SerialName("BuiltInKey") val builtInKey: String = "",

    /** Retained for migration and built-in identity; the UI shows one merged scheme name. */
    @SerialName("Category") val category: String = "自定义",
    @SerialName("Name") val name: String = "自定义 HUD",

    /** `Full` or `Simple`; see [AnimationMode]. */
    @SerialName("AnimationMode") val animationMode: String = "Full",

    @SerialName("TaglineTemplate") val taglineTemplate: String = "/// SYSTEM MONITOR",
    @SerialName("TitleTemplate") val titleTemplate: String = "系统状态",
    @SerialName("PrimaryTemplate") val primaryTemplate: String = "{cpu.frequency_ghz|0.00}",
    @SerialName("SecondaryTemplate") val secondaryTemplate: String = " GHz",
    @SerialName("RightTemplate") val rightTemplate: String = "{cpu.usage|0}",
    @SerialName("RightSuffix") val rightSuffix: String = "%",

    @SerialName("ProgressVariable") val progressVariable: String = "cpu.usage",
    @SerialName("ProgressMin") val progressMin: Double = 0.0,
    @SerialName("ProgressMax") val progressMax: Double = 100.0,

    @SerialName("LeftIcon") val leftIcon: String = "cpu",
    @SerialName("RightIcon") val rightIcon: String = "cpu",
    @SerialName("AccentColor") val accentColor: String = "#C6CA4C",
    @SerialName("ColorRules") val colorRules: List<HudColorRule> = emptyList(),

    /** Time category: day progress by default, or the remaining proportion to a daily target. */
    @SerialName("TimeTargetEnabled") val timeTargetEnabled: Boolean = false,
    @SerialName("TimeTarget") val timeTarget: String = "10:00:00",

    /** Optional per-profile GPU target. Empty means the first available adapter. */
    @SerialName("GpuAdapterId") val gpuAdapterId: String = "",

    /** `AutoBytes` or `Mbps`. */
    @SerialName("NetworkDisplayUnit") val networkDisplayUnit: String = "AutoBytes",

    /** `Total`, `Download`, `Upload` or `Max`. */
    @SerialName("NetworkPercentMode") val networkPercentMode: String = "Total",
    @SerialName("NetworkReferenceValue") val networkReferenceValue: Double = 100.0,
    @SerialName("NetworkReferenceUnit") val networkReferenceUnit: String = "MB/s",

    /** Probe target: IPv4/IPv6 literal or DNS host name. Port is used by TCP/UDP only. */
    @SerialName("PingTarget") val pingTarget: String = "1.1.1.1",
    @SerialName("ProbeProtocol") val probeProtocol: String = "ICMP",
    @SerialName("ProbePort") val probePort: Int = 443,
) {
    val animation: AnimationMode get() = AnimationMode.fromWire(animationMode)

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

private fun newId(): String = HudProfile.newId()
