package com.glacierglimmer.endfieldchargeplus.ui.state

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile

/**
 * Pure scheme ("方案") edit operations.
 *
 * Every mutation takes an [AppConfig] and returns a new one, so the ViewModels only ever call
 * `configRepository.update { ProfileEdits.…(it, …) }` and the behaviour stays unit-testable without
 * Android, DataStore or Compose.
 *
 * The rules mirror the Windows editor (`Customization/HudCustomizerView.axaml.cs`):
 *  * a built-in scheme is immutable — editing it creates a *copy* and leaves the preset untouched;
 *  * a deleted scheme is dropped from the carousel queue and the active selection moves to a
 *    surviving entry;
 *  * the carousel queue is identity based, so renaming a scheme never moves it.
 */
object ProfileEdits {

    /** The profile with [profileId], or null. */
    fun profileById(config: AppConfig, profileId: String?): HudProfile? =
        config.customHud.profiles.firstOrNull { it.id == profileId }

    /** The effective active profile, falling back to the built-in memory scheme. */
    fun activeProfile(config: AppConfig): HudProfile? = config.customHud.activeProfile()

    /** True when [profile] ships with the application and is therefore read-only. */
    fun isBuiltIn(profile: HudProfile?): Boolean =
        profile != null && (profile.isBuiltIn || profile.builtInKey.isNotBlank())

    /** A fresh, unused scheme id. */
    fun newProfileId(): String = HudProfile.newId()

    /** Replaces one profile in place; keeps list order and the active selection. */
    fun updateProfile(
        config: AppConfig,
        profileId: String,
        transform: (HudProfile) -> HudProfile,
    ): AppConfig {
        val profiles = config.customHud.profiles.map { profile ->
            if (profile.id == profileId) transform(profile) else profile
        }
        return config.copy(customHud = config.customHud.copy(profiles = profiles))
    }

    /** Creates a new custom scheme and selects it. */
    fun createCustom(
        config: AppConfig,
        name: String,
        titleTemplate: String,
        category: String = "自定义",
    ): AppConfig {
        val unique = uniqueName(config.customHud.profiles.map { it.name }, name)
        val profile = HudProfile(
            id = newProfileId(),
            isBuiltIn = false,
            builtInKey = "",
            category = category,
            name = unique,
            animationMode = "Full",
            titleTemplate = titleTemplate,
            leftIcon = "clock",
            rightIcon = "clock",
        )
        return config
            .withProfiles(config.customHud.profiles + profile)
            .withActiveProfile(profile.id)
    }

    /** Copies an existing scheme (built-in included) under a new name and selects the copy. */
    fun duplicate(config: AppConfig, profileId: String, name: String): AppConfig {
        val source = profileById(config, profileId) ?: return config
        val unique = uniqueName(config.customHud.profiles.map { it.name }, name)
        val copy = source.copy(
            id = newProfileId(),
            isBuiltIn = false,
            builtInKey = "",
            category = "自定义",
            name = unique,
        )
        return config
            .withProfiles(config.customHud.profiles + copy)
            .withActiveProfile(copy.id)
    }

    /** Renames a custom scheme. Built-in names are owned by the localization layer. */
    fun rename(config: AppConfig, profileId: String, name: String): AppConfig {
        val profile = profileById(config, profileId) ?: return config
        if (isBuiltIn(profile)) return config
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return config
        val unique = uniqueName(
            config.customHud.profiles.filter { it.id != profileId }.map { it.name },
            trimmed,
        )
        return updateProfile(config, profileId) { it.copy(name = unique) }
    }

    /**
     * Saves an edited built-in as a new custom scheme, leaving the built-in untouched — the
     * "save built-in as custom" behaviour of the desktop editor.
     */
    fun saveBuiltInAsCustom(
        config: AppConfig,
        profileId: String,
        edited: HudProfile,
        name: String,
    ): AppConfig {
        if (profileById(config, profileId) == null) return config
        val unique = uniqueName(config.customHud.profiles.map { it.name }, name)
        val copy = edited.copy(
            id = newProfileId(),
            isBuiltIn = false,
            builtInKey = "",
            category = "自定义",
            name = unique,
            colorRules = edited.colorRules.toList(),
        )
        return config
            .withProfiles(config.customHud.profiles + copy)
            .withActiveProfile(copy.id)
    }

    /** Deletes a custom scheme, its queue entry, and repairs the active selection. */
    fun deleteProfile(config: AppConfig, profileId: String): AppConfig {
        val profile = profileById(config, profileId) ?: return config
        if (isBuiltIn(profile)) return config
        val remaining = config.customHud.profiles.filterNot { it.id == profileId }
        val queue = cycleIds(config).filterNot { it == profileId }
        val active = if (config.customHud.activeProfileId == profileId) {
            remaining.firstOrNull()?.id.orEmpty()
        } else {
            config.customHud.activeProfileId
        }
        return config.copy(
            customHud = config.customHud.copy(
                profiles = remaining,
                cycleProfileIds = queue,
                activeProfileId = active,
            ),
        )
    }

    // ------------------------------------------------------------------ carousel queue

    /** The effective queue, materializing the legacy `null` form. */
    fun cycleIds(config: AppConfig): List<String> =
        config.customHud.cycleProfileIds ?: config.customHud.effectiveCycleProfileIds()

    /** Appends [profileId] to the queue when it is not present yet. */
    fun addCycleEntry(config: AppConfig, profileId: String): AppConfig {
        if (profileById(config, profileId) == null) return config
        val queue = cycleIds(config)
        if (queue.contains(profileId)) return config
        return config.withCycleIds(queue + profileId)
    }

    /** Removes the queue entry at [index]. */
    fun removeCycleEntry(config: AppConfig, index: Int): AppConfig {
        val queue = cycleIds(config)
        if (index !in queue.indices) return config
        return config.withCycleIds(queue.toMutableList().also { it.removeAt(index) })
    }

    /** Moves the queue entry at [index] by [delta] (−1 up, +1 down). */
    fun moveCycleEntry(config: AppConfig, index: Int, delta: Int): AppConfig {
        val queue = cycleIds(config).toMutableList()
        val target = index + delta
        if (index !in queue.indices || target !in queue.indices) return config
        val moved = queue.removeAt(index)
        queue.add(target, moved)
        return config.withCycleIds(queue)
    }

    /** Keeps every queued id that still resolves to a profile, preserving order. */
    fun pruneCycle(config: AppConfig): AppConfig {
        val known = config.customHud.profiles.map { it.id }.toSet()
        val queue = cycleIds(config).filter { it in known }.distinct()
        return config.withCycleIds(queue)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Makes [desired] unique among [existing] by appending ` 2`, ` 3`, … exactly like the desktop
     * editor's `MakeUniqueCustomName` (`HudCustomizerView.axaml.cs:623-636`).
     */
    fun uniqueName(existing: List<String>, desired: String, fallback: String = "Custom Profile"): String {
        val base = desired.trim().ifEmpty { fallback }
        if (existing.none { it.equals(base, ignoreCase = true) }) return base
        for (index in 2 until 1000) {
            val candidate = "$base $index"
            if (existing.none { it.equals(candidate, ignoreCase = true) }) return candidate
        }
        return "$base ${newProfileId().take(8)}"
    }

    /** True when a profile with [name] already exists (case-insensitive). */
    fun nameTaken(config: AppConfig, name: String, exceptProfileId: String? = null): Boolean =
        config.customHud.profiles.any {
            it.id != exceptProfileId && it.name.equals(name.trim(), ignoreCase = true)
        }

    /**
     * Compares everything a user can edit, ignoring identity and built-in bookkeeping.
     *
     * This is the Android equivalent of the desktop `ProfilesFunctionallyEqual`
     * (`HudCustomizerView.axaml.cs:582-621`): it decides whether saving a built-in really has to
     * create a modified copy.
     */
    fun functionallyEquals(a: HudProfile, b: HudProfile): Boolean =
        a.animationMode == b.animationMode &&
            a.taglineTemplate == b.taglineTemplate &&
            a.titleTemplate == b.titleTemplate &&
            a.primaryTemplate == b.primaryTemplate &&
            a.secondaryTemplate == b.secondaryTemplate &&
            a.rightTemplate == b.rightTemplate &&
            a.rightSuffix == b.rightSuffix &&
            a.progressVariable == b.progressVariable &&
            kotlin.math.abs(a.progressMin - b.progressMin) < 1e-6 &&
            kotlin.math.abs(a.progressMax - b.progressMax) < 1e-6 &&
            a.leftIcon.equals(b.leftIcon, ignoreCase = true) &&
            a.rightIcon.equals(b.rightIcon, ignoreCase = true) &&
            a.accentColor.equals(b.accentColor, ignoreCase = true) &&
            a.timeTargetEnabled == b.timeTargetEnabled &&
            a.timeTarget == b.timeTarget &&
            a.gpuAdapterId.equals(b.gpuAdapterId, ignoreCase = true) &&
            a.networkDisplayUnit.equals(b.networkDisplayUnit, ignoreCase = true) &&
            a.networkPercentMode.equals(b.networkPercentMode, ignoreCase = true) &&
            kotlin.math.abs(a.networkReferenceValue - b.networkReferenceValue) < 1e-6 &&
            a.networkReferenceUnit.equals(b.networkReferenceUnit, ignoreCase = true) &&
            a.pingTarget.equals(b.pingTarget, ignoreCase = true) &&
            a.probeProtocol.equals(b.probeProtocol, ignoreCase = true) &&
            a.probePort == b.probePort &&
            a.colorRules.size == b.colorRules.size &&
            a.colorRules.zip(b.colorRules).all { (x, y) ->
                x.variable.equals(y.variable, ignoreCase = true) &&
                    x.operator == y.operator &&
                    kotlin.math.abs(x.value - y.value) < 1e-6 &&
                    x.color.equals(y.color, ignoreCase = true)
            }
}

/** Replaces the profile list while keeping every other scheme setting. */
internal fun AppConfig.withProfiles(profiles: List<HudProfile>): AppConfig =
    copy(customHud = customHud.withProfiles(profiles))

/** Selects the active scheme by id. */
internal fun AppConfig.withActiveProfile(profileId: String): AppConfig =
    copy(customHud = customHud.copy(activeProfileId = profileId))

/** Replaces the carousel queue with an explicit (already ordered) id list. */
internal fun AppConfig.withCycleIds(ids: List<String>): AppConfig =
    copy(customHud = customHud.copy(cycleProfileIds = ids))

internal fun CustomHudSettings.withProfiles(profiles: List<HudProfile>): CustomHudSettings =
    copy(profiles = profiles)
