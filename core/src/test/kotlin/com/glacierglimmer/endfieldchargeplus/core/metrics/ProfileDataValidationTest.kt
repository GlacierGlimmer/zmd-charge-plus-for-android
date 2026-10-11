package com.glacierglimmer.endfieldchargeplus.core.metrics
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import org.junit.Assert.*
import org.junit.Test

class ProfileDataValidationTest {
    private val profile=HudProfile(primaryTemplate="{frame.window.fps}",secondaryTemplate="",rightTemplate="",
        rightSuffix="",taglineTemplate="",titleTemplate="",progressVariable="frame.window.percent")
    @Test fun `measured zero FPS is valid`() {
        val snapshot=MetricSnapshot(mapOf("frame.window.fps" to MetricValue.Number(0.0),"frame.window.percent" to MetricValue.Number(0.0)))
        assertTrue(ProfileDataValidation.missing(profile,snapshot).isEmpty())
    }
    @Test fun `missing or invalid frame readings are detected`() {
        assertTrue(ProfileDataValidation.missing(profile,MetricSnapshot.Empty).contains("frame.window.fps"))
        assertTrue(ProfileDataValidation.missing(profile,MetricSnapshot(mapOf("frame.window.fps" to MetricValue.Number(Double.NaN)))).isNotEmpty())
    }
    @Test fun `failed expressions are detected even without referenced keys`() {
        val invalid=profile.copy(primaryTemplate="{= 1 / 0}",progressVariable="= 0 / 0")
        assertTrue(ProfileDataValidation.missing(invalid,MetricSnapshot.Empty).isNotEmpty())
    }
}
