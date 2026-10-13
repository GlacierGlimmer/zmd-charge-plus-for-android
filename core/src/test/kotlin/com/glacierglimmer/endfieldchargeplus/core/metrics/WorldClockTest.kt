package com.glacierglimmer.endfieldchargeplus.core.metrics

import com.glacierglimmer.endfieldchargeplus.core.i18n.BuiltInProfileLocalization
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.json.EcpJson
import com.glacierglimmer.endfieldchargeplus.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WorldClockTest {
    private val world = BuiltInProfiles.all().single { it.builtInKey == BuiltInProfiles.TIME_WORLD_CLOCK }
    private val instant = Instant.parse("2026-01-01T02:30:00Z")
    private fun values(offset: Int, enabled: Boolean = false, at: Instant = instant) =
        WorldClockValues.values(world.copy(timeUtcOffsetMinutes = offset, timeTargetEnabled = enabled,
            timeTarget = "20:30:00"), at, UiLanguage.EN)

    @Test fun categoryOrderAndDefaultQueuePreserveSelection() {
        assertEquals(BuiltInProfileOrder.keys, BuiltInProfiles.all().map { it.builtInKey })
        val settings = CustomHudSettings.createDefault()
        assertEquals(BuiltInProfiles.MEMORY, settings.activeProfile()!!.builtInKey)
        assertFalse(settings.effectiveCycleProfileIds().contains(world.id))
        assertFalse(ConfigNormalizer.normalize(AppConfig(customHud = settings.copy(cycleProfileIds = null)))
            .customHud.effectiveCycleProfileIds().contains(world.id))
    }
    @Test fun labelsAndFractionalZonesAreBilingual() {
        assertEquals(39, WorldClockTimeZones.all.size)
        assertEquals(39, WorldClockTimeZones.all.map { it.minutes }.distinct().size)
        val mountain = WorldClockTimeZones.all.single { it.minutes == -420 }
        assertTrue(mountain.label(UiLanguage.ZH_CN).contains("山地时间"))
        assertEquals("UTC-07:00 Mountain Time (US & Canada)", mountain.label(UiLanguage.EN))
        assertEquals("World Clock", BuiltInProfileLocalization.forLanguage(world, UiLanguage.EN).name)
        assertEquals("Time", BuiltInProfileLocalization.forLanguage(world, UiLanguage.EN).category)
        assertEquals(MetricValue.Text("08:15:00"), values(345)[Variables.TIME_CURRENT])
        assertEquals(MetricValue.Text("UTC+05:45"), values(345)[Variables.TIME_UTC_OFFSET])
    }
    @Test fun zoneChangesDateAndDayProgressTogether() {
        val west = values(-420)
        assertEquals(MetricValue.Text("19:30:00"), west[Variables.TIME_CURRENT])
        assertEquals(MetricValue.Text("2025-12-31"), west[Variables.TIME_DATE])
        assertEquals(19.5 / 24 * 100, (west[Variables.TIME_DISPLAY_PROGRESS] as MetricValue.Number).value, 1e-10)
        assertEquals(MetricValue.Text("2026-01-02"), values(840, at = Instant.parse("2026-01-01T23:00:00Z"))[Variables.TIME_DATE])
        assertEquals(MetricValue.Text("UTC-07:00"), values(-420, at = Instant.parse("2026-07-01T12:00:00Z"))[Variables.TIME_UTC_OFFSET])
    }
    @Test fun targetUsesSelectedZoneAndRollsAtExactTime() {
        val before = values(-420, true)
        assertEquals(MetricValue.Text("Left 4%"), before[Variables.TIME_DISPLAY_STATUS_TEXT])
        assertEquals(3600.0, (before[Variables.TIME_TARGET_REMAINING_SECONDS] as MetricValue.Number).value, 0.0)
        assertEquals(MetricValue.Text("Left 100%"), values(-420, true, instant.plusSeconds(3600))[Variables.TIME_DISPLAY_STATUS_TEXT])
        assertEquals(MetricValue.Text("Left 96%"), values(-420, true, instant.plusSeconds(7200))[Variables.TIME_DISPLAY_STATUS_TEXT])
        assertEquals(MetricValue.Text("剩余4%"), WorldClockValues.values(world.copy(timeUtcOffsetMinutes = -420,
            timeTargetEnabled = true, timeTarget = "20:30:00"), instant, UiLanguage.ZH_CN)[Variables.TIME_DISPLAY_STATUS_TEXT])
    }
    @Test fun importExportAndReorderPreserveZoneTargetAndExplicitQueue() {
        val original = AppConfig()
        val queue = listOf(world.id, original.customHud.activeProfileId)
        val configured = original.copy(customHud = original.customHud.copy(activeProfileId = world.id,
            cycleProfileIds = queue, profiles = original.customHud.profiles.map {
                if (it.id == world.id) it.copy(timeUtcOffsetMinutes = -420, timeTargetEnabled = true, timeTarget = "18:25:00") else it
            }))
        val imported = ConfigNormalizer.normalize(EcpJson.decode(AppConfig.serializer(),
            EcpJson.encode(AppConfig.serializer(), configured)).getOrThrow())
        assertEquals(queue, imported.customHud.cycleProfileIds)
        assertEquals(world.id, imported.customHud.activeProfileId)
        val kept = imported.customHud.profileById(world.id)!!
        assertEquals(-420, kept.timeUtcOffsetMinutes)
        assertTrue(kept.timeTargetEnabled)
        assertEquals("18:25:00", kept.timeTarget)
        val repaired = EcpJson.decode(HudProfile.serializer(), """{"TimeUtcOffsetMinutes":"345"}""").getOrThrow()
        assertEquals(345, repaired.timeUtcOffsetMinutes)
        assertEquals(0, WorldClockTimeZones.normalize(999))
    }
    @Test fun perProfileRenderingAndValidationDoNotBorrowAnotherClock() {
        val stale = MetricSnapshot(mapOf(Variables.TIME_CURRENT to MetricValue.Text("wrong")))
        val west = WorldClockValues.effectiveSnapshot(world.copy(timeUtcOffsetMinutes = -420), stale, instant = instant)
        val east = WorldClockValues.effectiveSnapshot(world.copy(timeUtcOffsetMinutes = 345), stale, instant = instant)
        assertEquals(MetricValue.Text("19:30:00"), west[Variables.TIME_CURRENT])
        assertEquals(MetricValue.Text("08:15:00"), east[Variables.TIME_CURRENT])
        assertEquals(MetricValue.Text("wrong"), stale[Variables.TIME_CURRENT])
        assertTrue(ProfileDataValidation.missing(world, MetricSnapshot()).isEmpty())
        assertTrue(ProfileDataValidation.missing(world.copy(primaryTemplate = "{unreadable.metric}"), MetricSnapshot()).contains("unreadable.metric"))
        val local = BuiltInProfiles.all().single { it.builtInKey == BuiltInProfiles.TIME_DAY_PROGRESS }
        assertSame(stale, WorldClockValues.effectiveSnapshot(local, stale))
        val copy = world.copy(isBuiltIn = false, builtInKey = "", id = "copy", category = "自定义", timeUtcOffsetMinutes = 345)
        assertEquals(345, ConfigNormalizer.normalize(AppConfig(customHud = CustomHudSettings(profiles = listOf(copy))))
            .customHud.profileById("copy")!!.timeUtcOffsetMinutes)
    }
}
