package com.glacierglimmer.endfieldchargeplus.overlay

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile

/**
 * Resolves the ordered carousel queue ("自动轮播") of the HUD.
 *
 * Ported from `HudSettingsNormalizer.GetSanitizedCycleProfileIds` (`:773-781`) and
 * `CustomHudRuntime.ResolveCycleProfiles` (`:528-544`), see `docs/audit/01-windows-core.md` §4.7:
 *
 *  * the queue is identity based and order preserving;
 *  * blank entries and ids that no longer exist are dropped — this is the "a cycled scheme was
 *    deleted" cleanup rule the settings editor also applies when a profile is deleted;
 *  * duplicates (case-insensitive) are dropped;
 *  * an **explicitly empty** queue is valid and falls back to the active scheme, so the HUD always
 *    has content to render;
 *  * a legacy configuration (`CycleProfileIds == null`) uses every `系统` scheme plus the day
 *    progress scheme, which is what [com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings.effectiveCycleProfileIds]
 *    already provides.
 *
 * Everything here is pure so the whole cycle policy is covered by JVM tests.
 */
object HudCycleOrder {

    /** The queue in display order, never empty when the configuration has any scheme at all. */
    fun resolve(config: AppConfig): List<HudProfile> {
        val profiles = config.customHud.profiles
        val byId = HashMap<String, HudProfile>(profiles.size)
        for (profile in profiles) {
            byId[profile.id.trim().lowercase()] = profile
        }
        val seen = HashSet<String>()
        val ordered = ArrayList<HudProfile>(profiles.size)
        for (rawId in config.customHud.effectiveCycleProfileIds()) {
            val key = rawId.trim().lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            val profile = byId[key] ?: continue
            ordered.add(profile)
        }
        if (ordered.isNotEmpty()) return ordered
        return listOfNotNull(config.customHud.activeProfile())
    }

    /** The scheme that must be shown when cycling is off (or when the queue is empty). */
    fun activeProfile(config: AppConfig): HudProfile? = config.customHud.activeProfile()

    /** The index of [profileId] in the queue, or 0 when it is not part of it. */
    fun indexOf(config: AppConfig, profileId: String?): Int {
        if (profileId.isNullOrEmpty()) return 0
        val index = resolve(config).indexOfFirst { it.id.equals(profileId, ignoreCase = true) }
        return if (index >= 0) index else 0
    }

    /** The next queue position, wrapping around. */
    fun next(config: AppConfig, currentIndex: Int): Int {
        val size = resolve(config).size
        return if (size <= 0) 0 else ((currentIndex % size) + 1) % size
    }

    /** Brings a stored index back into range after the queue shrank. */
    fun clampIndex(config: AppConfig, index: Int): Int {
        val size = resolve(config).size
        return if (size <= 0) 0 else index.coerceIn(0, size - 1)
    }
}
