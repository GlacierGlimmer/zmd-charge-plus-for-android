package com.glacierglimmer.endfieldchargeplus.core.i18n

import org.junit.Assert.*
import org.junit.Test

class LanguageCatalogTest {
    @Test fun `offline catalog contains Java languages and ECP additions without duplicates`() {
        val catalog=LanguageCatalog.languages
        assertEquals(146,catalog.size)
        assertEquals(143,catalog.count {it.minecraftCode!=null})
        assertEquals(catalog.size,catalog.map {it.code}.toSet().size)
        assertEquals(catalog.map {it.code}.toSet(),UiLanguage.entries.map {it.wire}.toSet())
        for(locale in catalog) {
            assertTrue(locale.displayName.isNotBlank())
            assertTrue(locale.languageWord.isNotBlank())
            assertNotNull(LanguageCatalog::class.java.getResource("/localization/${locale.code}.json"))
            val language=UiLanguage.resolve(locale.code,"en-US")
            for(key in listOf("Settings","Data Management","Done","Global font size and UI scale"))
                assertTrue(LanguageCatalog.translate(key,language).isNotBlank())
        }
    }
    @Test fun `system aliases preserve script region and fictional explicit choices`() {
        for((source,expected) in listOf("zh-Hant-HK" to "zh-HK","sr-Cyrl-RS" to "sr-Cyrl",
            "nb-NO" to "no-NO","en_pt" to "en-x-pirate","bo-IN" to "bo-CN","iw-IL" to "he-IL"))
            assertEquals(source,expected,UiLanguage.resolve("Auto",source).wire)
        assertEquals("ja-JP",UiLanguage.resolve("ja-JP","bo-CN").wire)
        assertEquals("en-US",UiLanguage.resolve("Auto","xx-YY").wire)
    }
    @Test fun `translation keeps resolved argument values and unknown user text`() {
        val japanese=UiLanguage.resolve("ja-JP","en-US")
        val value=LanguageCatalog.translate("Current: v0.1.3",japanese)
        assertTrue(value.contains("0.1.3"))
        assertFalse(value.contains("{0}"))
        val userText="My HUD {cpu.usage|0.0}"
        assertEquals(userText,LanguageCatalog.translate(userText,japanese))
    }
}
