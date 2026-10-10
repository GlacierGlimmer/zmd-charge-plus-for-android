package com.glacierglimmer.endfieldchargeplus.overlay
import com.glacierglimmer.endfieldchargeplus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CycleAnimationTest {
    @Test fun `both cycle modes override queued schemes without modifying saved animation`() {
        val a=HudProfile(id="a",animationMode="Full");val b=HudProfile(id="b",animationMode="Simple")
        for(mode in listOf("Simple","Full")) {
            val config=AppConfig(customHud=CustomHudSettings(profiles=listOf(a,b),activeProfileId="a",autoCycle=true,cycleProfileIds=listOf("a","b"),cycleAnimationMode=mode))
            assertEquals(mode,HudCycleOrder.renderedProfile(config,0)!!.animationMode)
            assertEquals(mode,HudCycleOrder.renderedProfile(config,1)!!.animationMode)
            assertEquals("Full",config.customHud.profiles[0].animationMode)
            assertEquals("Simple",config.customHud.profiles[1].animationMode)
        }
    }
    @Test fun `turning cycling off restores the active schemes own animation`() {
        val config=AppConfig(customHud=CustomHudSettings(profiles=listOf(HudProfile(id="a",animationMode="Full")),activeProfileId="a",autoCycle=false,cycleAnimationMode="Simple"))
        assertEquals("Full",HudCycleOrder.renderedProfile(config,0)!!.animationMode)
    }
}
