package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreOperationsTest {

    private val memoryId = BuiltInProfiles.idOf(BuiltInProfiles.MEMORY)

    @Test
    fun `createCustom appends and selects a new scheme`() {
        val settings = ProfileStoreOperations.createCustom(CustomHudSettings.createDefault())

        val created = settings.profileById(settings.activeProfileId)
        assertTrue(created != null)
        assertFalse(created!!.isBuiltIn)
        assertEquals("", created.builtInKey)
        assertEquals(ConfigNormalizer.CUSTOM_CATEGORY, created.category)
        assertEquals(CustomHudSettings.createDefault().profiles.size + 1, settings.profiles.size)
    }

    @Test
    fun `duplicateProfile copies the source and keeps names unique`() {
        val settings = CustomHudSettings.createDefault()
        val source = settings.profileById(BuiltInProfiles.idOf(BuiltInProfiles.CPU))!!

        val once = ProfileStoreOperations.duplicateProfile(settings, source.id, "我的副本")
        val twice = ProfileStoreOperations.duplicateProfile(once, source.id, "我的副本")

        val firstCopy = once.profileById(once.activeProfileId)!!
        assertEquals("我的副本", firstCopy.name)
        assertNotEquals(source.id, firstCopy.id)
        assertFalse(firstCopy.isBuiltIn)
        assertEquals(firstCopy.id, once.profiles[once.profiles.indexOfFirst { it.id == source.id } + 1].id)

        val secondCopy = twice.profileById(twice.activeProfileId)!!
        assertEquals("我的副本 2", secondCopy.name)
    }

    @Test
    fun `renameProfile renames a custom scheme and refuses a built-in`() {
        val withCustom = ProfileStoreOperations.createCustom(CustomHudSettings.createDefault(), "旧名字")
        val customId = withCustom.activeProfileId

        val renamed = ProfileStoreOperations.renameProfile(withCustom, customId, "新名字")
        assertEquals("新名字", renamed.profileById(customId)!!.name)

        val builtInId = BuiltInProfiles.idOf(BuiltInProfiles.CPU)
        assertEquals(withCustom, ProfileStoreOperations.renameProfile(withCustom, builtInId, "不许改名"))
    }

    @Test
    fun `deleteProfile removes the scheme from the carousel and re-points the active scheme`() {
        var settings = ProfileStoreOperations.createCustom(CustomHudSettings.createDefault(), "临时方案")
        val customId = settings.activeProfileId
        settings = ProfileStoreOperations.setCycleEntry(settings, customId, true)
        assertTrue(settings.effectiveCycleProfileIds().contains(customId))

        val after = ProfileStoreOperations.deleteProfile(settings, customId)

        assertFalse(after.profiles.any { it.id == customId })
        assertFalse(after.effectiveCycleProfileIds().contains(customId))
        assertTrue(after.cycleProfileIds!!.none { it == customId })
        assertEquals(memoryId, after.activeProfileId)
    }

    @Test
    fun `deleteProfile refuses a built-in scheme`() {
        val settings = CustomHudSettings.createDefault()
        val builtInId = BuiltInProfiles.idOf(BuiltInProfiles.CPU)
        assertEquals(settings, ProfileStoreOperations.deleteProfile(settings, builtInId))
    }

    @Test
    fun `saveBuiltInAsCustom leaves the built-in untouched`() {
        val settings = CustomHudSettings.createDefault()
        val builtIn = settings.profileById(BuiltInProfiles.idOf(BuiltInProfiles.CPU))!!

        val after = ProfileStoreOperations.saveBuiltInAsCustom(settings, builtIn.id)
        val copy = after.profileById(after.activeProfileId)!!

        assertFalse(copy.isBuiltIn)
        assertEquals("", copy.builtInKey)
        assertEquals(ConfigNormalizer.CUSTOM_CATEGORY, copy.category)
        assertEquals("CPU ${Strings.t("（已更改）", "(modified)")}", copy.name)
        assertEquals(settings.profiles.size + 1, after.profiles.size)
        assertTrue(after.profileById(builtIn.id)!!.isBuiltIn)
    }

    @Test
    fun `default scheme names follow the active language`() {
        val previous = Strings.current()
        try {
            Strings.setLanguage(UiLanguage.EN)
            val english = ProfileStoreOperations.createCustom(CustomHudSettings.createDefault())
            val englishName = english.profileById(english.activeProfileId)!!.name
            assertFalse("an English session must not produce a Chinese scheme name", englishName.any { it.code > 0x2E7F })
            assertTrue(englishName.startsWith("Custom Profile"))

            Strings.setLanguage(UiLanguage.ZH_CN)
            val chinese = ProfileStoreOperations.createCustom(CustomHudSettings.createDefault())
            val chineseName = chinese.profileById(chinese.activeProfileId)!!.name
            assertTrue(chineseName.startsWith("自定义方案"))
        } finally {
            Strings.setLanguage(previous)
        }
    }

    @Test
    fun `moveCycleEntry reorders without losing entries`() {
        val settings = CustomHudSettings.createDefault()
        val queue = settings.cycleProfileIds!!
        val expected = queue.toMutableList()
        val moved = expected.removeAt(0)
        expected.add(2, moved)

        val after = ProfileStoreOperations.moveCycleEntry(settings, 0, 2)

        assertEquals(expected, after.cycleProfileIds)
        assertEquals(queue.toSet(), after.cycleProfileIds!!.toSet())
        assertEquals(settings, ProfileStoreOperations.moveCycleEntry(settings, 0, 99))
    }

    @Test
    fun `moveCycleEntry materializes a legacy null queue`() {
        val legacy = CustomHudSettings(
            profiles = BuiltInProfiles.all(),
            activeProfileId = memoryId,
            cycleProfileIds = null,
        )
        val after = ProfileStoreOperations.moveCycleEntry(legacy, 0, 1)
        assertEquals(legacy.effectiveCycleProfileIds().size, after.cycleProfileIds!!.size)
    }

    @Test
    fun `setCycleEntry adds removes and never duplicates`() {
        val settings = CustomHudSettings.createDefault()
        val extra = ProfileStoreOperations.createCustom(settings, "额外方案")
        val extraId = extra.activeProfileId

        val added = ProfileStoreOperations.setCycleEntry(extra, extraId, true)
        assertTrue(added.effectiveCycleProfileIds().contains(extraId))
        assertEquals(added, ProfileStoreOperations.setCycleEntry(added, extraId, true))

        val removed = ProfileStoreOperations.setCycleEntry(added, extraId, false)
        assertFalse(removed.effectiveCycleProfileIds().contains(extraId))
    }

    @Test
    fun `previewProfile falls back to the active scheme`() {
        val settings = CustomHudSettings.createDefault()
        val active = settings.activeProfile()
        assertEquals(active, ProfileStoreOperations.previewProfile(settings))
        assertEquals(active, ProfileStoreOperations.previewProfile(settings, "missing"))
        assertEquals(
            settings.profileById(BuiltInProfiles.idOf(BuiltInProfiles.CPU)),
            ProfileStoreOperations.previewProfile(settings, BuiltInProfiles.idOf(BuiltInProfiles.CPU)),
        )
        assertNull(ProfileStoreOperations.previewProfile(CustomHudSettings(profiles = emptyList())))
    }

    @Test
    fun `a duplicated built-in becomes an editable custom scheme`() {
        val settings = CustomHudSettings.createDefault()
        val builtIn = settings.profileById(BuiltInProfiles.idOf(BuiltInProfiles.NETWORK))!!

        val after = ProfileStoreOperations.duplicateProfile(settings, builtIn.id)
        val copy: HudProfile = after.profileById(after.activeProfileId)!!

        assertFalse(copy.isBuiltIn)
        assertEquals("", copy.builtInKey)
        assertEquals(builtIn.primaryTemplate, copy.primaryTemplate)
    }
}
