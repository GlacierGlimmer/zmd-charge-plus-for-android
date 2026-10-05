package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import java.util.Locale

/**
 * The scheme ("方案") operations the settings UI needs, as pure functions over
 * [CustomHudSettings]. Every operation returns a new immutable value; nothing here touches
 * DataStore, Android state or Compose.
 *
 * The semantics follow `HudCustomizerView.axaml.cs` from the desktop editions:
 *
 *  * built-in schemes are read-only — the UI offers "save as a custom scheme" instead of "delete";
 *  * a saved built-in copy is a new custom scheme with a new id and the legacy two-level
 *    category/name folded into one name;
 *  * deleting a scheme removes it from the carousel queue and re-points the active scheme;
 *  * the carousel queue is identity based, so renaming never changes a queue position.
 */
object ProfileStoreOperations {

    private const val TAG = "ProfileStore"
    const val CUSTOM_CATEGORY = ConfigNormalizer.CUSTOM_CATEGORY

    /** Appends a new empty custom scheme and selects it (desktop `OnAddProfile`). */
    fun createCustom(settings: CustomHudSettings, name: String? = null): CustomHudSettings {
        val customCount = settings.profiles.count { !it.isBuiltIn && it.builtInKey.isBlank() }
        val requested = name?.trim().orEmpty()
        val baseName = requested.ifEmpty {
            // The scheme name is user-visible text, so it follows the active UI language; only the
            // category stays the stable storage key and is localized at display time.
            Strings.t("自定义方案", "Custom Profile") + " ${customCount + 1}"
        }
        val profile = HudProfile(
            id = HudProfile.newId(),
            isBuiltIn = false,
            builtInKey = "",
            category = CUSTOM_CATEGORY,
            name = uniqueCustomName(settings, baseName),
            animationMode = AnimationMode.FULL.wire,
            titleTemplate = Strings.t("系统状态", "System Status"),
            leftIcon = "clock",
            rightIcon = "clock",
        )
        return settings.copy(profiles = settings.profiles + profile, activeProfileId = profile.id)
    }

    /** Copies [profileId] (built-in or custom) into a new custom scheme right after the source. */
    fun duplicateProfile(
        settings: CustomHudSettings,
        profileId: String,
        name: String? = null,
    ): CustomHudSettings {
        val source = settings.profileById(profileId)
            ?: settings.profiles.firstOrNull { it.id.equals(profileId, ignoreCase = true) }
            ?: return settings

        val requested = name?.trim().orEmpty()
        val copy = source.copy(
            id = HudProfile.newId(),
            isBuiltIn = false,
            builtInKey = "",
            category = CUSTOM_CATEGORY,
            name = uniqueCustomName(settings, requested.ifEmpty { "${source.name} " + Strings.t("副本", "copy") }),
        )
        val index = settings.profiles.indexOfFirst { it.id == source.id }.coerceAtLeast(0)
        val profiles = settings.profiles.toMutableList().apply { add(index + 1, copy) }
        return settings.copy(profiles = profiles, activeProfileId = copy.id)
    }

    /** Renames a custom scheme. Built-in schemes are read-only, so this is a no-op for them. */
    fun renameProfile(settings: CustomHudSettings, profileId: String, newName: String): CustomHudSettings {
        val target = settings.profileById(profileId) ?: return settings
        if (target.isBuiltIn || target.builtInKey.isNotBlank()) {
            AppLog.i(TAG, "Built-in scheme '${target.name}' cannot be renamed")
            return settings
        }
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return settings
        return settings.copy(
            profiles = settings.profiles.map { if (it.id == target.id) it.copy(name = trimmed) else it },
        )
    }

    /**
     * Deletes a custom scheme, removes it from the carousel queue and re-points `ActiveProfileId`
     * to the memory scheme (or the first remaining one). Built-in schemes are refused.
     */
    fun deleteProfile(settings: CustomHudSettings, profileId: String): CustomHudSettings {
        val target = settings.profileById(profileId) ?: return settings
        if (target.isBuiltIn || target.builtInKey.isNotBlank()) {
            AppLog.i(TAG, "Built-in scheme '${target.name}' cannot be deleted")
            return settings
        }

        val remaining = settings.profiles.filterNot { it.id == target.id }
        val queue = settings.effectiveCycleProfileIds().filterNot { it == target.id }.distinct()
        val active = if (settings.activeProfileId == target.id) {
            remaining.firstOrNull { it.builtInKey.equals(BuiltInProfiles.MEMORY, ignoreCase = true) }?.id
                ?: remaining.firstOrNull()?.id.orEmpty()
        } else {
            settings.activeProfileId
        }
        return settings.copy(profiles = remaining, cycleProfileIds = queue, activeProfileId = active)
    }

    /**
     * Saves a built-in scheme as an independent custom scheme (desktop `OnDeleteProfile`'s
     * counterpart for read-only schemes). The built-in itself is untouched.
     */
    fun saveBuiltInAsCustom(
        settings: CustomHudSettings,
        builtInProfileId: String,
        newName: String? = null,
    ): CustomHudSettings {
        val source = settings.profileById(builtInProfileId) ?: return settings
        if (!source.isBuiltIn && source.builtInKey.isBlank()) {
            AppLog.i(TAG, "'${source.name}' is not a built-in scheme; use duplicateProfile instead")
            return settings
        }
        val requested = newName?.trim().orEmpty()
        val copy = source.copy(
            id = HudProfile.newId(),
            isBuiltIn = false,
            builtInKey = "",
            category = CUSTOM_CATEGORY,
            name = uniqueCustomName(settings, requested.ifEmpty { "${source.name} " + Strings.t("（已更改）", "(modified)") }),
        )
        return settings.copy(profiles = settings.profiles + copy, activeProfileId = copy.id)
    }

    /** Moves a carousel entry in place (desktop `OnCycleMoveUp` / `OnCycleMoveDown`). */
    fun moveCycleEntry(settings: CustomHudSettings, fromIndex: Int, toIndex: Int): CustomHudSettings {
        val queue = settings.effectiveCycleProfileIds()
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return settings
        val mutable = queue.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return settings.copy(cycleProfileIds = mutable)
    }

    /** Adds or removes one scheme from the carousel queue (no duplicates, order preserved). */
    fun setCycleEntry(settings: CustomHudSettings, profileId: String, included: Boolean): CustomHudSettings {
        val profile = settings.profileById(profileId)
            ?: settings.profiles.firstOrNull { it.id.equals(profileId, ignoreCase = true) }
            ?: return settings
        val queue = settings.effectiveCycleProfileIds().toMutableList()
        val index = queue.indexOfFirst { it.equals(profile.id, ignoreCase = true) }
        when {
            included && index < 0 -> queue.add(profile.id)
            !included && index >= 0 -> queue.removeAt(index)
            else -> return settings
        }
        return settings.copy(cycleProfileIds = queue)
    }

    /** The scheme the preview/overlay should render for [profileId] (falls back to the active one). */
    fun previewProfile(settings: CustomHudSettings, profileId: String? = null): HudProfile? =
        settings.profileById(profileId) ?: settings.activeProfile() ?: settings.profiles.firstOrNull()

    /** Appends `" 2"`, `" 3"`, ... like the desktop `MakeUniqueCustomName`. */
    fun uniqueCustomName(settings: CustomHudSettings, base: String): String {
        val existing = settings.profiles.map { it.name.trim().lowercase(Locale.US) }.toSet()
        if (base.lowercase(Locale.US) !in existing) return base
        for (index in 2..999) {
            val candidate = "$base $index"
            if (candidate.lowercase(Locale.US) !in existing) return candidate
        }
        return "$base ${HudProfile.newId().take(8)}"
    }
}
