package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableAliases
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.template.ProfileCapabilityFilter
import com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine
import com.glacierglimmer.endfieldchargeplus.metrics.Capability
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities

/**
 * Device-capability filtering for the persisted schemes.
 *
 * The actual stripping is done by the pure core function [ProfileCapabilityFilter] (the Android port
 * of the desktop `RemoveUnsupportedReferences` rule): unsupported `{variable}` tokens become empty,
 * `ProgressVariable` is cleared and colour rules are dropped. Nothing is destroyed — the caller
 * keeps the pre-migration JSON under the `config_previous` DataStore key before persisting.
 *
 * This class adds the two things the repository needs on top:
 *
 *  * **capability verdicts → unsupported variable patterns** ([unsupportedVariables]), including the
 *    family forms `gpu.*` and `cpu.coreN.usage` that a capability scan naturally produces;
 *  * **pattern expansion** — the core filter compares exact variable names, so each pattern is
 *    resolved against the variables the schemes actually reference before delegating.
 */
object CapabilityProfileFilter {

    /** Reason key used for "never scanned"; such a verdict must not strip anything. */
    private const val REASON_NOT_SCANNED = "capability_not_scanned"

    /** Filters every scheme. Returns [settings] unchanged when nothing needs to change. */
    fun filter(settings: CustomHudSettings, unsupportedVariables: Set<String>): CustomHudSettings {
        if (unsupportedVariables.isEmpty()) return settings
        val unavailable = settings.profiles
            .flatMap { profileReferences(it) }
            .filterNot { isAvailable(it, unsupportedVariables) }
            .toSet()
        if (unavailable.isEmpty()) return settings
        val profiles = settings.profiles.map { ProfileCapabilityFilter.filter(it, unavailable) }
        if (profiles == settings.profiles) return settings
        return settings.copy(profiles = profiles)
    }

    /** Filters one scheme. */
    fun filter(profile: HudProfile, unsupportedVariables: Set<String>): HudProfile {
        if (unsupportedVariables.isEmpty()) return profile
        val unavailable = profileReferences(profile)
            .filterNot { isAvailable(it, unsupportedVariables) }
            .toSet()
        if (unavailable.isEmpty()) return profile
        return ProfileCapabilityFilter.filter(profile, unavailable)
    }

    /** True when [variable] can be produced; unsupported entries support `prefix.*` and `coreN` forms. */
    fun isAvailable(variable: String, unsupportedVariables: Set<String>): Boolean {
        val canonical = VariableAliases.canonical(variable.trim())
        if (canonical.isBlank()) return true
        return unsupportedVariables.none { matches(it, canonical) }
    }

    /**
     * Matches one unsupported-variable pattern:
     *  * `gpu.*`      — every variable with that prefix;
     *  * `cpu.coreN.usage` — `N` stands for one or more digits (`cpu.core3.usage`);
     *  * anything else — an exact, case-insensitive name.
     */
    fun matches(pattern: String, variable: String): Boolean {
        val candidate = pattern.trim()
        if (candidate.isEmpty() || variable.isEmpty()) return false
        if (candidate.endsWith(".*")) {
            return variable.startsWith(candidate.dropLast(1), ignoreCase = true)
        }
        if (candidate.contains('N')) {
            val regex = Regex(
                "^" + Regex.escape(candidate).replace("N", "\\E\\d+\\Q") + "$",
                RegexOption.IGNORE_CASE,
            )
            if (regex.matches(variable)) return true
        }
        return candidate.equals(variable, ignoreCase = true)
    }

    /**
     * The unsupported-variable patterns implied by a capability scan.
     *
     * A verdict that was never produced (`capability_not_scanned`, for example
     * [HardwareCapabilities.Unknown]) is treated as "unknown", not as "unsupported": an unscanned
     * device must not have its configuration rewritten. `capability_partial` counts as
     * unsupported, because a partially readable metric cannot be promised as a real number.
     */
    fun unsupportedVariables(capabilities: HardwareCapabilities): Set<String> {
        val result = LinkedHashSet<String>()

        fun failed(capability: Capability): Boolean =
            !capability.supported && capability.reasonKey != REASON_NOT_SCANNED

        if (failed(capabilities.cpuTemperature)) result += Variables.CPU_TEMPERATURE_C
        if (failed(capabilities.cpuPerCoreUsage)) result += "${Variables.CPU_PER_CORE_PREFIX}N.usage"

        val gpuFamily = listOf(
            capabilities.gpuUsage,
            capabilities.gpuFrequency,
            capabilities.gpuTemperature,
            capabilities.gpuMemory,
        )
        if (gpuFamily.all { failed(it) }) {
            result += "gpu.*"
        } else {
            if (failed(capabilities.gpuUsage)) result += Variables.GPU_USAGE
            if (failed(capabilities.gpuFrequency)) result += Variables.GPU_FREQUENCY_GHZ
            if (failed(capabilities.gpuTemperature)) result += Variables.GPU_TEMPERATURE_C
            if (failed(capabilities.gpuMemory)) {
                result += Variables.GPU_MEMORY_USED_BYTES
                result += Variables.GPU_MEMORY_TOTAL_BYTES
            }
        }

        if (failed(capabilities.batteryTemperature)) result += Variables.BATTERY_TEMPERATURE_C
        if (failed(capabilities.batteryCurrent)) result += Variables.BATTERY_CURRENT_MA
        if (failed(capabilities.batteryVoltage)) result += Variables.BATTERY_VOLTAGE_MV

        if (failed(capabilities.memoryDetail)) {
            result += Variables.MEMORY_CACHED_BYTES
            result += Variables.MEMORY_SWAP_TOTAL_BYTES
            result += Variables.MEMORY_SWAP_USED_BYTES
        }

        if (failed(capabilities.storage)) result += "disk.*"

        if (failed(capabilities.networkTraffic)) {
            result += Variables.NETWORK_DOWNLOAD_BPS
            result += Variables.NETWORK_UPLOAD_BPS
            result += Variables.NETWORK_DOWNLOAD_TOTAL_BYTES
            result += Variables.NETWORK_UPLOAD_TOTAL_BYTES
        }
        if (failed(capabilities.wifiSsid)) result += Variables.NETWORK_WIFI_SSID

        // `probe.status_text` is generated locally on failure and stays available on purpose.
        if (failed(capabilities.probeIcmp) && failed(capabilities.probeTcp) && failed(capabilities.probeUdp)) {
            Variables.PROBE_ALL
                .filterNot { it == Variables.PROBE_STATUS_TEXT }
                .forEach { result += it }
        }

        return result
    }

    /** Every variable a scheme references: all seven template fields, the progress field, the rules. */
    private fun profileReferences(profile: HudProfile): List<String> = buildList {
        addAll(
            TemplateEngine.extractKeys(
                profile.taglineTemplate,
                profile.titleTemplate,
                profile.primaryTemplate,
                profile.secondaryTemplate,
                profile.rightTemplate,
                profile.rightSuffix,
            ),
        )
        addAll(TemplateEngine.extractExpressionKeys(profile.progressVariable))
        profile.colorRules.forEach { if (it.variable.isNotBlank()) add(it.variable) }
    }
}
