package com.glacierglimmer.endfieldchargeplus.hud

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class WorldClockRenderingTest {
    private val world = BuiltInProfiles.all().single { it.builtInKey == BuiltInProfiles.TIME_WORLD_CLOCK }
    private val builder = HudStateBuilderImpl(Clock.fixed(Instant.parse("2026-01-01T02:30:00Z"), ZoneOffset.UTC))
    @Test fun overlayPreviewAndIslandUseOneZoneAndInstant() {
        val west = world.copy(timeUtcOffsetMinutes = -420)
        val data = builder.build(west, MetricSnapshot(), UiLanguage.EN)
        assertEquals("World Clock", data.title)
        assertEquals("19:30:00", data.primaryText)
        assertEquals(" UTC-07:00", data.secondaryText)
        assertEquals(19.5 / 24, data.progress, 1e-10)
        assertEquals("世界时钟", builder.build(west, MetricSnapshot(), UiLanguage.ZH_CN).title)
        assertEquals("08:15:00", builder.build(world.copy(timeUtcOffsetMinutes = 345), MetricSnapshot(), UiLanguage.EN).primaryText)
    }
    @Test fun targetModeUsesSelectedZoneAndChangesAreNotIgnored() {
        val target = world.copy(timeUtcOffsetMinutes = -420, timeTargetEnabled = true, timeTarget = "20:30:00")
        val data = builder.build(target, MetricSnapshot(), UiLanguage.EN)
        assertEquals("Left 4%", data.rightText)
        assertEquals(1.0 / 24, data.progress, 1e-10)
        assertEquals(data.progress, builder.resolveProgress(target, MetricSnapshot()), 0.0)
        assertFalse(ProfileEdits.functionallyEquals(world, world.copy(timeUtcOffsetMinutes = 345)))
    }
}
