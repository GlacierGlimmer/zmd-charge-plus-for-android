package com.glacierglimmer.endfieldchargeplus.localization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import android.content.res.Resources

/**
 * Applies the persisted language preference to the shared core string table and exposes the
 * resolved language as Compose state so that every screen, dialog and error recomposes at once.
 *
 * The HUD renderer and the foreground notification read the same [Strings] singleton, which is why
 * the settings UI must never keep its own copy of a user-visible string.
 */
class LanguageController(private val container: EcpContainer) {

    /** The language the application is currently rendering in. */
    var language: UiLanguage by mutableStateOf(
        Strings.resolve(FALLBACK_PREFERENCE, systemLanguageTag()),
    )
        private set

    init { Strings.setLanguage(language) }

    /**
     * Resolves [preference] (`Auto`, `zh-CN`, `en-US`) against the device locale and pushes the
     * result into the shared table. Safe to call repeatedly; only a real change is propagated.
     */
    fun applyPreference(preference: String?) {
        val resolved = Strings.resolve(
            preference ?: FALLBACK_PREFERENCE,
            systemLanguageTag(),
        )
        if (Strings.current() != resolved) {
            Strings.setLanguage(resolved)
        }
        if (language != resolved) {
            language = resolved
        }
    }

    /** Persists a manual language choice; the config flow then re-applies it everywhere. */
    fun choose(language: AppLanguage, scope: CoroutineScope) {
        scope.launch {
            container.configRepository.update { current ->
                current.copy(uiLanguage = language.wire)
            }
        }
    }

    /**
     * "Auto" is stored as a distinct preference: it is not the same as "the language we happen to
     * resolve to right now", so the selector can show it as its own choice.
     */
    companion object {
        const val FALLBACK_PREFERENCE = "Auto"

        fun systemLanguageTag(): String =
            Resources.getSystem().configuration.locales.get(0)?.toLanguageTag()
                ?: Locale.getDefault().toLanguageTag()

        /** Preferences offered by the selector, in display order. */
        val preferences: List<AppLanguage> = listOf(
            AppLanguage.AUTO,
            AppLanguage.SIMPLIFIED_CHINESE,
            AppLanguage.ENGLISH,
        )

        /** True when [preference] means "follow the system language". */
        fun isFollowingSystem(preference: String?): Boolean =
            (preference ?: FALLBACK_PREFERENCE).trim().lowercase(Locale.US) == "auto"
    }
}
