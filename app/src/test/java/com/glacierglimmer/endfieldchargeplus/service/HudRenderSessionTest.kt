package com.glacierglimmer.endfieldchargeplus.service

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayController
import org.junit.Assert.*
import org.junit.Test

class HudRenderSessionTest {
    private val profile = HudProfile(id = "memory")
    private class Output : OverlayController {
        var attached = false
        val frames = mutableListOf<Boolean>()
        override fun isShowing() = attached
        override fun canShow() = true
        override fun show() { attached = true }
        override fun hide() { attached = false }
        override fun update(data: HudRenderData, animate: Boolean) { frames += animate; attached = true }
        override fun applyConfig(config: AppConfig) = Unit
        override fun persistPosition(x: Int, y: Int) = Unit
        override fun release() { attached = false }
        override fun onConfigurationChanged() = Unit
        override fun isTransitioning() = false
    }

    @Test fun `early sampling cannot suppress the first configured overlay reveal`() {
        val output = Output(); var builds = 0; var selections = 0
        val session = HudRenderSession(output, { builds++; HudRenderData() }, { selections++ }, {})
        repeat(20) { session.render(profile, animate = false) }
        assertEquals(0, builds); assertEquals(0, selections)
        assertEquals("", session.displayedProfileId); assertFalse(output.attached)
        session.configure(AppConfig())
        session.render(profile, animate = false)
        assertEquals(listOf(true), output.frames)
        assertEquals(1, builds); assertEquals(1, selections)
        assertEquals(profile.id, session.displayedProfileId)
        repeat(20) { session.render(profile, animate = false) }
        assertEquals(1, output.frames.count { it })
    }

    @Test fun `persistent overlay recovers a detached output without a display mode toggle`() {
        val output = Output()
        val session = HudRenderSession(output, { HudRenderData() }, {}, {})
        session.configure(AppConfig()); session.render(profile, false)
        output.hide(); session.render(profile, false)
        assertTrue(output.attached); assertEquals(2, output.frames.size)
    }

    @Test fun `hidden transient output stays hidden until an explicit reveal event`() {
        val output = Output()
        val session = HudRenderSession(output, { HudRenderData() }, {}, {})
        session.configure(AppConfig().let { it.copy(android = it.android.copy(alwaysVisible = false)) })
        session.render(profile, false); output.hide()
        repeat(100) { session.render(profile, false) }
        assertFalse(output.attached); assertEquals(1, output.frames.size)
        session.render(profile, true)
        assertTrue(output.attached); assertEquals(listOf(true, true), output.frames)
    }

    @Test fun `disabled HUD and configured island never attach the default overlay`() {
        val output = Output(); var islandFrames = 0
        val session = HudRenderSession(output, { HudRenderData() }, {}, { islandFrames++ })
        session.configure(AppConfig(hudEnabled = false)); session.render(profile, true)
        assertEquals(0, output.frames.size); assertEquals("", session.displayedProfileId)
        session.configure(AppConfig().let { it.copy(android = it.android.copy(displayMode = "Island")) })
        session.render(profile, false)
        assertEquals(1, islandFrames); assertEquals(0, output.frames.size)
    }
}
