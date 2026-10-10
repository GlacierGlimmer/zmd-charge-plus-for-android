package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the bilingual string tables.
 *
 * The important product rule is that switching to English may not leave any Chinese text in the UI,
 * dialogs, permission explanations, island states or errors. The key-addressed strings are the ones
 * the island/permission/capability contracts hand to the UI, so they are checked most strictly.
 */
class EcpMessagesContractTest {

    /** CJK ideographs plus the full-width punctuation block. */
    private val cjk = Regex("[\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF]")

    @Before
    fun setUp() {
        Strings.setLanguage(UiLanguage.EN)
    }

    @Test
    fun everyKeyHasBothLanguages() {
        assertTrue("The string table must not be empty", EcpMessages.keys().size > 40)
        EcpMessages.keys().forEach { key ->
            assertFalse("Missing Chinese text for $key", EcpMessages.zh(key).isNullOrBlank())
            assertFalse("Missing English text for $key", EcpMessages.en(key).isNullOrBlank())
        }
    }

    @Test
    fun englishTableContainsNoChinese() {
        Strings.setLanguage(UiLanguage.EN)
        EcpMessages.keys().forEach { key ->
            val text = EcpMessages.t(key)
            assertFalse("English text for $key still contains CJK: $text", cjk.containsMatchIn(text))
        }
    }

    @Test
    fun switchingLanguageChangesTheRenderedText() {
        Strings.setLanguage(UiLanguage.EN)
        val english = EcpMessages.t("island_state_unsupported")
        Strings.setLanguage(UiLanguage.ZH_CN)
        val chinese = EcpMessages.t("island_state_unsupported")
        assertEquals("Not supported", english)
        assertEquals("不支持", chinese)
    }

    @Test
    fun unknownKeyDegradesToAsciiWithoutChinese() {
        Strings.setLanguage(UiLanguage.EN)
        val text = EcpMessages.t("some_future_key")
        assertFalse(text.isBlank())
        assertFalse("Fallback leaked CJK: $text", cjk.containsMatchIn(text))
    }

    /**
     * The island layer freezes these keys and picks its wording from the Android string resources;
     * the Kotlin table must stay identical so the Display page and the notification never disagree.
     */
    @Test
    fun frozenIslandKeysMatchTheIslandLayer() {
        val expectedEnglish = mapOf(
            "island_provider_android_system" to "Android system",
            "island_state_available" to "Available",
            "island_state_unsupported" to "Not supported",
            "island_state_not_authorized" to "Not authorized",
            "island_state_notifications_disabled" to "Notifications are disabled",
            "island_state_channel_disabled" to "The notification channel is disabled",
            "island_state_promotion_check_failed" to "Could not verify island eligibility",
            "island_state_not_promotable" to "Not available on this device or scenario",
            "island_state_publish_failed" to "Publishing failed",
            "island_limit_no_custom_layout" to "No custom layout",
            "island_limit_no_left_right_split" to "No left/right split",
            "island_limit_scenario_restricted" to "Restricted usage scenarios",
            "island_limit_platform_colorized_required" to "Requires the platform colorized shape",
            "island_limit_throttled_updates" to "The system throttles update frequency",
            "island_limit_vendor_review_required" to "Vendor review and authorization required",
        )
        Strings.setLanguage(UiLanguage.EN)
        expectedEnglish.forEach { (key, english) ->
            assertTrue("The island layer emits $key but the UI cannot resolve it", EcpMessages.knows(key))
            assertEquals(key, english, EcpMessages.t(key))
        }
        Strings.setLanguage(UiLanguage.ZH_CN)
        assertEquals("不支持", EcpMessages.t("island_state_unsupported"))
        assertEquals("尚未授权", EcpMessages.t("island_state_not_authorized"))
    }
}
