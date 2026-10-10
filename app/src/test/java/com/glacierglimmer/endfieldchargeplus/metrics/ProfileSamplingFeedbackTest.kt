package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSamplingFeedbackTest {
    @Test fun `snapshot rendering cannot feed unbounded immediate sampling back into the collector`() = runTest {
        val profile = HudProfile(id = "memory")
        val selected = MutableStateFlow<HudProfile?>(profile)
        var samples = 0
        lateinit var scheduler: SamplingScheduler
        scheduler = SamplingScheduler({ AppConfig() }, onSnapshot = {
            check(samples < 100) { "render feedback caused a sampling storm" }
            updateSamplingProfile(selected, profile.copy(), scheduler::requestImmediate)
        }, dispatcher = StandardTestDispatcher(testScheduler))
        scheduler.register(object : MetricCollector {
            override val id = "memory-test"
            override val tier = SamplingTier.NORMAL
            override suspend fun collect(into: MutableMap<String, MetricValue>) {
                samples++; into["memory.usage"] = MetricValue.Number(samples.toDouble())
            }
        })
        scheduler.setDemand(MetricDemand(outputActive = true, hudVisible = true, foregroundUi = false, screenOn = true))
        scheduler.start(); runCurrent()
        assertEquals(1, samples)
        repeat(1_000) { updateSamplingProfile(selected, profile.copy(), scheduler::requestImmediate) }
        runCurrent(); assertEquals(1, samples)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(samples in 2..3)
        scheduler.stop()
    }

    @Test fun `editing or switching the actual profile requests one fresh sample`() {
        val profile = HudProfile(id = "memory")
        val selected = MutableStateFlow<HudProfile?>(null)
        var requests = 0
        val refresh = { requests++; Unit }
        updateSamplingProfile(selected, profile, refresh)
        updateSamplingProfile(selected, profile.copy(), refresh)
        assertEquals(1, requests)
        updateSamplingProfile(selected, profile.copy(primaryTemplate = "updated"), refresh)
        assertEquals(2, requests)
        updateSamplingProfile(selected, null, refresh)
        assertEquals(3, requests)
    }
}
