package com.glacierglimmer.endfieldchargeplus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The complete scheme ("方案") configuration. Mirrors `CustomHudSettings` from the Windows
 * edition, including the automatic carousel queue and the custom HTTP sources.
 */
@Serializable
data class CustomHudSettings(
    @SerialName("AutoCycle") val autoCycle: Boolean = false,
    @SerialName("CycleSeconds") val cycleSeconds: Int = 10,

    /**
     * Ordered profile ids used by automatic cycling. `null` means a legacy settings file that
     * predates the explicit queue; the normalizer migrates it once.
     */
    @SerialName("CycleProfileIds") val cycleProfileIds: List<String>? = null,

    /**
     * `Simple` or `Full`. While auto cycling this overrides each profile's own animation mode for
     * the transitions between queue entries.
     */
    @SerialName("CycleAnimationMode") val cycleAnimationMode: String = "Simple",
    @SerialName("ActiveProfileId") val activeProfileId: String = "",

    /** DeepSeek API key, stored protected (never in clear text, never logged). */
    @SerialName("DeepSeekApiKeyProtected") val deepSeekApiKeyProtected: String = "",

    /** Peak (高峰) windows, `HH:mm-HH:mm` pairs separated by `;`. */
    @SerialName("DeepSeekPeakWindows") val deepSeekPeakWindows: String = "09:00-12:00;14:00-18:00",

    @SerialName("Profiles") val profiles: List<HudProfile> = emptyList(),
    @SerialName("HttpSources") val httpSources: List<CustomHttpSource> = emptyList(),
) {
    fun profileById(id: String?): HudProfile? = profiles.firstOrNull { it.id == id }

    fun activeProfile(): HudProfile? =
        profileById(activeProfileId) ?: profiles.firstOrNull { it.builtInKey == "system.memory" } ?: profiles.firstOrNull()

    /**
     * The effective carousel queue. A legacy file (`CycleProfileIds == null`) uses every profile in
     * the 系统 category plus the day progress scheme, exactly like the desktop normalizer.
     */
    fun effectiveCycleProfileIds(): List<String> {
        val explicit = cycleProfileIds
        if (explicit != null) {
            val known = explicit.filter { id -> profiles.any { it.id == id } }
            return known
        }
        return profiles
            .filter { it.category == "系统" || it.builtInKey.equals("time.day-progress", ignoreCase = true) }
            .map { it.id }
    }

    companion object {
        fun createDefault(): CustomHudSettings {
            val profiles = BuiltInProfiles.all()
            val defaultProfile = profiles.firstOrNull { it.builtInKey.equals("system.memory", ignoreCase = true) }
                ?: profiles.first()
            return CustomHudSettings(
                profiles = profiles,
                activeProfileId = defaultProfile.id,
                cycleProfileIds = profiles
                    .filter { it.category == "系统" || it.builtInKey.equals("time.day-progress", ignoreCase = true) }
                    .map { it.id },
                cycleAnimationMode = "Simple",
            )
        }
    }
}
