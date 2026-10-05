package com.glacierglimmer.endfieldchargeplus.core.i18n

import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage

/**
 * The resolved user interface language.
 *
 * The product rule (identical on every ECP platform) is: a `Auto` preference resolves any
 * Chinese locale to Simplified Chinese and everything else to English; an explicit choice always
 * wins. [AppLanguage] is the persisted preference (`Auto` / `zh-CN` / `en-US`), this enum is the
 * concrete language the application renders in.
 *
 * This type is declared here (rather than next to the string tables) because it is part of the
 * public contract used by the HUD state builder, the overlay, the notifications, the island
 * providers and the settings UI.
 */
enum class UiLanguage(val wire: String, val tag: String) {
    /** 简体中文 */
    ZH_CN("zh-CN", "zh-Hans"),

    /** English */
    EN("en-US", "en"),
    ;

    val isEnglish: Boolean get() = this == EN

    /** Localization key suffix used to pick the right field of a bilingual pair. */
    val key: String get() = if (isEnglish) "en" else "zh"

    companion object {
        /**
         * Resolves the persisted preference plus the device language tag.
         *
         * @param preference the persisted `AppConfig.uiLanguage` value (`Auto`, `zh-CN`, `en-US`).
         * @param systemLanguageTag the device's current language tag, for example `zh-Hant-TW`.
         */
        fun resolve(preference: String?, systemLanguageTag: String?): UiLanguage {
            when (preference?.trim()?.lowercase()) {
                "zh-cn", "zh", "zh-hans" -> return ZH_CN
                "en-us", "en" -> return EN
            }
            val tag = systemLanguageTag.orEmpty().lowercase()
            return if (tag.startsWith("zh")) ZH_CN else EN
        }

        /** Resolves from the strongly typed preference record. */
        fun fromAppLanguage(language: AppLanguage?, systemLanguageTag: String?): UiLanguage =
            resolve(language?.wire, systemLanguageTag)

        /** The persisted preference value that corresponds to this concrete language. */
        fun preferenceOf(language: UiLanguage): String = language.wire
    }
}
