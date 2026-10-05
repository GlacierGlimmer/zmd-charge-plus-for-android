package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import com.glacierglimmer.endfieldchargeplus.ui.state.withCycleIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheme edit operations — the pure reducers the Content ViewModel applies through
 * `configRepository.update`.
 */
class ProfileEditsTest {

    private val base = AppConfig()

    private fun memoryId(): String =
        base.customHud.profiles.first { it.builtInKey == BuiltInProfiles.MEMORY }.id

    @Test
    fun createsACustomSchemeAndSelectsIt() {
        val updated = ProfileEdits.createCustom(base, "My HUD", "System Status")
        assertEquals(base.customHud.profiles.size + 1, updated.customHud.profiles.size)
        val created = updated.customHud.activeProfile()!!
        assertEquals("My HUD", created.name)
        assertEquals("System Status", created.titleTemplate)
        assertFalse(created.isBuiltIn)
        assertEquals("", created.builtInKey)
        assertEquals(created.id, updated.customHud.activeProfileId)
    }

    @Test
    fun updatingABuiltInThroughTheEditorCreatesAnIndependentCopy() {
        val id = memoryId()
        val builtIn = ProfileEdits.profileById(base, id)!!
        val edited = builtIn.copy(titleTemplate = "MEM")
        val updated = ProfileEdits.saveBuiltInAsCustom(base, id, edited, "Memory copy")

        // The preset is untouched …
        val preset = ProfileEdits.profileById(updated, id)!!
        assertTrue(ProfileEdits.isBuiltIn(preset))
        assertEquals("内存", preset.titleTemplate)
        // … and the copy is selected, custom and carries the edit.
        val copy = updated.customHud.profiles.first { it.name == "Memory copy" }
        assertFalse(copy.isBuiltIn)
        assertEquals("", copy.builtInKey)
        assertEquals("MEM", copy.titleTemplate)
        assertEquals(copy.id, updated.customHud.activeProfileId)
    }

    @Test
    fun duplicateCopiesAnySchemeUnderANewName() {
        val updated = ProfileEdits.duplicate(base, memoryId(), "Memory clone")
        val copy = updated.customHud.profiles.first { it.name == "Memory clone" }
        assertFalse(copy.isBuiltIn)
        assertEquals("", copy.builtInKey)
        assertTrue(copy.id != memoryId())
        assertEquals(copy.id, updated.customHud.activeProfileId)
    }

    @Test
    fun renameOnlyAffectsCustomSchemes() {
        val custom = ProfileEdits.createCustom(base, "First", "T")
        val id = custom.customHud.activeProfile()!!.id
        val renamed = ProfileEdits.rename(custom, id, "Second")
        assertEquals("Second", ProfileEdits.profileById(renamed, id)!!.name)

        val builtInId = memoryId()
        val untouched = ProfileEdits.rename(base, builtInId, "Nope")
        assertEquals(base.customHud.profiles.first { it.id == builtInId }.name, ProfileEdits.profileById(untouched, builtInId)!!.name)
    }

    @Test
    fun deleteRemovesTheSchemeAndItsQueueEntry() {
        val custom = ProfileEdits.createCustom(base, "Temp", "T")
        val id = custom.customHud.activeProfile()!!.id
        val queued = ProfileEdits.addCycleEntry(custom, id)
        assertTrue(ProfileEdits.cycleIds(queued).contains(id))

        val deleted = ProfileEdits.deleteProfile(queued, id)
        assertTrue(deleted.customHud.profiles.none { it.id == id })
        assertFalse(ProfileEdits.cycleIds(deleted).contains(id))
        assertTrue(deleted.customHud.activeProfileId != id)
        assertNotNull(deleted.customHud.activeProfile())
    }

    @Test
    fun deleteRefusesBuiltInSchemes() {
        val id = memoryId()
        val updated = ProfileEdits.deleteProfile(base, id)
        assertTrue(updated.customHud.profiles.any { it.id == id })
    }

    @Test
    fun theCarouselQueueKeepsOrderAndIgnoresDuplicates() {
        val ids = base.customHud.profiles.map { it.id }
        var config = base.withCycleIds(listOf(ids[0], ids[1], ids[2]))
        config = ProfileEdits.addCycleEntry(config, ids[1])
        assertEquals(listOf(ids[0], ids[1], ids[2]), ProfileEdits.cycleIds(config))

        config = ProfileEdits.moveCycleEntry(config, 2, -1)
        assertEquals(listOf(ids[0], ids[2], ids[1]), ProfileEdits.cycleIds(config))
        config = ProfileEdits.moveCycleEntry(config, 0, 1)
        assertEquals(listOf(ids[2], ids[0], ids[1]), ProfileEdits.cycleIds(config))
        config = ProfileEdits.removeCycleEntry(config, 1)
        assertEquals(listOf(ids[2], ids[1]), ProfileEdits.cycleIds(config))
    }

    @Test
    fun movingOutsideTheQueueIsANoOp() {
        val ids = base.customHud.profiles.map { it.id }
        val config = base.withCycleIds(listOf(ids[0], ids[1]))
        assertEquals(listOf(ids[0], ids[1]), ProfileEdits.cycleIds(ProfileEdits.moveCycleEntry(config, 0, -1)))
        assertEquals(listOf(ids[0], ids[1]), ProfileEdits.cycleIds(ProfileEdits.moveCycleEntry(config, 1, 1)))
        assertEquals(listOf(ids[0], ids[1]), ProfileEdits.cycleIds(ProfileEdits.removeCycleEntry(config, 7)))
    }

    @Test
    fun pruningDropsReferencesToDeletedSchemes() {
        val config = base.withCycleIds(listOf(base.customHud.profiles[0].id, "ghost", "ghost"))
        assertEquals(listOf(base.customHud.profiles[0].id), ProfileEdits.cycleIds(ProfileEdits.pruneCycle(config)))
    }

    @Test
    fun uniqueNamesMatchTheDesktopBehaviour() {
        assertEquals("Custom", ProfileEdits.uniqueName(emptyList(), "Custom"))
        assertEquals("Custom 2", ProfileEdits.uniqueName(listOf("Custom"), "Custom"))
        assertEquals("Custom 3", ProfileEdits.uniqueName(listOf("Custom", "custom 2"), "Custom"))
        assertEquals("Custom", ProfileEdits.uniqueName(listOf("Other"), "  Custom  "))
    }

    @Test
    fun functionalEqualityIgnoresIdentityAndName() {
        val original = ProfileEdits.profileById(base, memoryId())!!
        val renamedCopy = original.copy(id = "other-id", name = "Renamed")
        assertTrue(ProfileEdits.functionallyEquals(original, renamedCopy))
        assertFalse(ProfileEdits.functionallyEquals(original, renamedCopy.copy(titleTemplate = "x")))
        assertFalse(ProfileEdits.functionallyEquals(original, renamedCopy.copy(progressMax = 90.0)))
    }
}
