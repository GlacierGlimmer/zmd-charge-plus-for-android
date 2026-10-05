package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Language resolution is a product rule: `Auto` follows the system locale, any `zh*` tag resolves to
 * Simplified Chinese, everything else to English, and an explicit choice always wins.
 */
class LanguageResolutionTest {

    @Test
    fun automaticFollowsTheSystemLocale() {
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh-Hans-CN"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh-Hant-TW"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", "en-GB"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", "ja-JP"))
    }

    @Test
    fun explicitPreferenceWinsOverTheSystemLocale() {
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("zh-CN", "en-US"))
        assertEquals(UiLanguage.EN, Strings.resolve("en-US", "zh-CN"))
    }

    @Test
    fun unknownPreferenceFallsBackToTheSystemLocale() {
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("", "zh-CN"))
        assertEquals(UiLanguage.EN, Strings.resolve("de-DE", "en-US"))
    }

    @Test
    fun thePersistedPreferenceRoundTrips() {
        assertEquals("en-US", AppLanguage.ENGLISH.wire)
        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.wire)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromWire("en-US"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromWire("zh-cn"))
        assertEquals(AppLanguage.AUTO, AppLanguage.fromWire("nonsense"))
        assertEquals(UiLanguage.EN, UiLanguage.preferenceOf(UiLanguage.EN).let { Strings.resolve(it, "zh-CN") })
    }

    @Test
    fun theControllerTreatsOnlyAutoAsFollowingTheSystem() {
        assertTrue(LanguageController.isFollowingSystem("Auto"))
        assertTrue(LanguageController.isFollowingSystem("  auto "))
        assertTrue(LanguageController.isFollowingSystem(null))
        assertFalse(LanguageController.isFollowingSystem("zh-CN"))
        assertFalse(LanguageController.isFollowingSystem("en-US"))
    }

    @Test
    fun theSelectorOffersEveryPreferenceExactlyOnce() {
        assertEquals(
            listOf(AppLanguage.AUTO, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.ENGLISH),
            LanguageController.preferences,
        )
        assertEquals(LanguageController.preferences.size, LanguageController.preferences.distinct().size)
    }
}
