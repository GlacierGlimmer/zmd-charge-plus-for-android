package com.glacierglimmer.endfieldchargeplus.core.i18n

import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [Strings]: language resolution, literal translation and dictionary integrity. */
class StringsTest {

    @After
    fun resetLanguage() {
        Strings.setLanguage(UiLanguage.EN)
    }

    @Test
    fun `resolve follows the ECP rule unchanged`() {
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh-Hans-CN"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh-Hant-TW"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh-TW"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("Auto", "zh"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", "en-US"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", "ja-JP"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", ""))
        // An explicit preference always wins over the device language.
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("zh-CN", "ja-JP"))
        assertEquals(UiLanguage.EN, Strings.resolve("en-US", "zh-CN"))
        assertEquals(UiLanguage.ZH_CN, Strings.resolve("zh", "en-US"))
        assertEquals(UiLanguage.EN, Strings.resolve("Auto", "EN-GB"))
    }

    @Test
    fun `resolve delegates to UiLanguage`() {
        for (preference in listOf("Auto", "zh-CN", "en-US", "", "nonsense")) {
            for (tag in listOf("zh-Hans", "zh-Hant-HK", "en-US", "ja-JP", "")) {
                assertEquals(
                    "preference=$preference tag=$tag",
                    UiLanguage.resolve(preference, tag),
                    Strings.resolve(preference, tag),
                )
            }
        }
    }

    @Test
    fun `initialize resolves and stores the language`() {
        assertEquals(UiLanguage.ZH_CN, Strings.initialize(AppLanguage.AUTO, "zh-Hans"))
        assertEquals(UiLanguage.ZH_CN, Strings.current())
        assertEquals(UiLanguage.EN, Strings.initialize("en-US", "zh-CN"))
        assertEquals(UiLanguage.EN, Strings.current())
    }

    @Test
    fun `t picks the wording of the current language`() {
        Strings.setLanguage(UiLanguage.ZH_CN)
        assertEquals("电池", Strings.t("电池", "Battery"))
        assertFalse(Strings.isEnglish())
        Strings.setLanguage(UiLanguage.EN)
        assertEquals("Battery", Strings.t("电池", "Battery"))
        assertTrue(Strings.isEnglish())
    }

    @Test
    fun `translateLiteral works in both directions`() {
        Strings.setLanguage(UiLanguage.EN)
        assertEquals("Variables", Strings.translateLiteral("变量库"))
        assertEquals("Endfield Charge Plus Settings", Strings.translateLiteral("Endfield Charge Plus 设置"))
        assertEquals("Save & Apply", Strings.translateLiteral("保存并应用"))
        // An unknown literal is returned unchanged, null becomes empty.
        assertEquals("not in the dictionary", Strings.translateLiteral("not in the dictionary"))
        assertEquals("", Strings.translateLiteral(null))
        assertEquals("", Strings.translateLiteral(""))

        Strings.setLanguage(UiLanguage.ZH_CN)
        assertEquals("变量库", Strings.translateLiteral("Variables"))
        assertEquals("变量库", Strings.translateLiteral("变量库"))
        assertEquals("unknown", Strings.translateLiteral("unknown"))
    }

    @Test
    fun `the dictionary is a faithful copy of the desktop table`() {
        assertTrue("dictionary looks truncated: ${Strings.zhToEn.size}", Strings.zhToEn.size >= 110)
        val hint = Strings.zhToEn.entries.first { it.key.startsWith("{}模板支持") }.value
        assertTrue("unexpected template hint wording: $hint", hint.startsWith("Templates use {variable|format}."))
        assertTrue(hint.contains("if(), min/max/avg/sum/clamp/round"))
        val duplicates = Strings.zhToEn.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("the desktop EnToZh reverse map would throw for $duplicates", duplicates.isEmpty())
        for ((zh, en) in Strings.zhToEn) {
            assertTrue("blank Chinese key", zh.isNotBlank())
            assertTrue("blank English value for '$zh'", en.isNotBlank())
            assertTrue("Chinese key has no CJK: '$zh'", zh.any { it.isCjk() })
        }
    }

    @Test
    fun `every English literal is free of CJK characters`() {
        Strings.setLanguage(UiLanguage.EN)
        val offenders = Strings.zhToEn.values.filter { value -> value.any { it.isCjk() } }
        assertTrue("English values still contain CJK: $offenders", offenders.isEmpty())
        // The whole UI dictionary rendered in English must not leak CJK either.
        for (zh in Strings.zhToEn.keys) {
            val translated = Strings.translateLiteral(zh)
            assertTrue("translation of '$zh' leaked CJK: '$translated'", translated.none { it.isCjk() })
            assertFalse(translated.contains("变量"))
        }
    }

    /** CJK ideographs, CJK punctuation and full-width forms. */
    private fun Char.isCjk(): Boolean =
        this in '\u3000'..'\u303f' || this in '\u4e00'..'\u9fff' || this in '\uff00'..'\uffef'
}
