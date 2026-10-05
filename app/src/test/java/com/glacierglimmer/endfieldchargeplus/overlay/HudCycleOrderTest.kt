package com.glacierglimmer.endfieldchargeplus.overlay

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [HudCycleOrder]: the ordered carousel queue, the "a cycled scheme was deleted" cleanup and
 * the fallbacks the desktop runtime relies on (`docs/audit/01-windows-core.md` §4.7).
 */
class HudCycleOrderTest {

    private val defaults = CustomHudSettings.createDefault()

    private val profileIds = defaults.profiles.map { it.id }

    private fun config(custom: CustomHudSettings = defaults): AppConfig = AppConfig(customHud = custom)

    @Test
    fun `the default queue is the system category plus day progress, in configuration order`() {
        val expected = listOf(
            BuiltInProfiles.idOf(BuiltInProfiles.BATTERY),
            BuiltInProfiles.idOf(BuiltInProfiles.CPU),
            BuiltInProfiles.idOf(BuiltInProfiles.MEMORY),
            BuiltInProfiles.idOf(BuiltInProfiles.GPU),
            BuiltInProfiles.idOf(BuiltInProfiles.NETWORK),
            BuiltInProfiles.idOf(BuiltInProfiles.DISK),
            BuiltInProfiles.idOf(BuiltInProfiles.TIME_DAY_PROGRESS),
        )
        assertEquals(expected, HudCycleOrder.resolve(config()).map { it.id })
    }

    @Test
    fun `an explicit queue preserves order and drops unknown, blank and duplicate ids`() {
        val queue = listOf(profileIds[2], "does-not-exist", "", profileIds[0], profileIds[2])
        val resolved = HudCycleOrder.resolve(config(defaults.copy(cycleProfileIds = queue)))
        assertEquals(listOf(profileIds[2], profileIds[0]), resolved.map { it.id })
    }

    @Test
    fun `an explicitly empty queue falls back to the active scheme`() {
        val resolved = HudCycleOrder.resolve(config(defaults.copy(cycleProfileIds = emptyList())))
        assertEquals(listOf(defaults.activeProfile()?.id), resolved.map { it.id })
        assertEquals(1, resolved.size)
    }

    @Test
    fun `a legacy null queue is migrated to the system category plus day progress`() {
        val resolved = HudCycleOrder.resolve(config(defaults.copy(cycleProfileIds = null)))
        assertEquals(HudCycleOrder.resolve(config()).map { it.id }, resolved.map { it.id })
    }

    @Test
    fun `deleting a cycled scheme removes it from the queue and clamps the index`() {
        val removed = profileIds[2]
        val shrunk = defaults.copy(profiles = defaults.profiles.filterNot { it.id == removed })
        val cfg = config(shrunk)
        assertTrue(HudCycleOrder.resolve(cfg).none { it.id == removed })
        assertEquals(HudCycleOrder.resolve(cfg).size - 1, HudCycleOrder.clampIndex(cfg, 99))
        assertEquals(0, HudCycleOrder.clampIndex(cfg, -5))
    }

    @Test
    fun `next wraps around the queue`() {
        val cfg = config()
        val size = HudCycleOrder.resolve(cfg).size
        assertEquals(1, HudCycleOrder.next(cfg, 0))
        assertEquals(0, HudCycleOrder.next(cfg, size - 1))
        // An out-of-range index is treated like the first entry before advancing.
        assertEquals(1, HudCycleOrder.next(cfg, size))
    }

    @Test
    fun `indexOf finds a queued scheme and defaults to the first entry`() {
        val cfg = config()
        assertEquals(2, HudCycleOrder.indexOf(cfg, profileIds[2]))
        assertEquals(0, HudCycleOrder.indexOf(cfg, "not-in-the-queue"))
        assertEquals(0, HudCycleOrder.indexOf(cfg, null))
    }

    @Test
    fun `the active scheme is the configured one when cycling is off`() {
        val cfg = config()
        assertEquals(defaults.activeProfile()?.id, HudCycleOrder.activeProfile(cfg)?.id)
        // An empty active id falls back to the default memory scheme, exactly like the desktop.
        val blankActive = config(defaults.copy(activeProfileId = ""))
        assertEquals(BuiltInProfiles.idOf(BuiltInProfiles.MEMORY), HudCycleOrder.activeProfile(blankActive)?.id)
    }

    @Test
    fun `a configuration without any scheme resolves to an empty queue`() {
        val empty = CustomHudSettings(profiles = emptyList())
        assertTrue(HudCycleOrder.resolve(config(empty)).isEmpty())
        assertEquals(0, HudCycleOrder.next(config(empty), 3))
        assertEquals(0, HudCycleOrder.clampIndex(config(empty), 3))
    }
}
