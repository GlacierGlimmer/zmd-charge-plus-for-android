package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scheduler behaviour on virtual time: one pass per tier cadence, failure isolation, demand
 * throttling and the "no concurrent run of the same collector" guarantee.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SamplingSchedulerTest {

    @Test fun `removed HTTP fields disappear and failed reads invalidate old numbers`() = runTest {
        var state=0
        val scheduler=SamplingScheduler({ config() },dispatcher=StandardTestDispatcher(testScheduler))
        scheduler.register(RecordingCollector("http",SamplingTier.NORMAL) { into ->
            when(state) {
                0 -> into["custom.api.temp"]=MetricValue.Number(42.0)
                1 -> Unit
                2 -> into["custom.api.other"]=MetricValue.Number(99.0)
                else -> throw IllegalStateException("failed")
            }
        })
        scheduler.start(); runCurrent()
        assertEquals(42.0,scheduler.snapshot.value.numberOrNull("custom.api.temp")!!,0.0)
        state=1; scheduler.requestImmediate(); runCurrent(); assertTrue(scheduler.snapshot.value.values.isEmpty())
        state=2; scheduler.requestImmediate(); runCurrent()
        assertEquals(99.0,scheduler.snapshot.value.numberOrNull("custom.api.other")!!,0.0)
        state=3; scheduler.requestImmediate(); runCurrent()
        assertTrue(scheduler.snapshot.value.values["custom.api.other"] is MetricValue.Unavailable)
        scheduler.stop()
    }

    @Test
    fun `slowing sampling does not collect an extra sample or retain the old delay`() = runTest {
        var current = config(fast = 100L)
        val scheduler = SamplingScheduler({ current }, dispatcher = StandardTestDispatcher(testScheduler))
        val collector = RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        }
        scheduler.register(collector)
        scheduler.setDemand(MetricDemand(outputActive = true, hudVisible = true, foregroundUi = true, screenOn = true))
        scheduler.start()
        runCurrent()
        val before = collector.invocations.get()
        current = config(fast = 2_000L)
        scheduler.configurationChanged()
        runCurrent()
        assertEquals(before, collector.invocations.get())
        advanceTimeBy(200L); runCurrent()
        assertEquals("the old 100ms delay must be cancelled", before, collector.invocations.get())
        advanceTimeBy(1_801L); runCurrent()
        assertTrue(collector.invocations.get() > before)
        scheduler.stop()
    }

    private fun config(
        fast: Long = 100L,
        normal: Long = 100L,
        slow: Long = 100L,
        idle: Long = 100L,
    ) = AppConfig(
        android = AndroidSettings(
            fastRefreshMs = fast,
            normalRefreshMs = normal,
            slowRefreshMs = slow,
            idleRefreshMs = idle,
            throttleWhenHidden = true,
            throttleWhenScreenOff = true,
            screenOffRefreshMs = 500L,
        ),
    )

    private class RecordingCollector(
        override val id: String,
        override val tier: SamplingTier,
        private val action: suspend (MutableMap<String, MetricValue>) -> Unit,
    ) : MetricCollector {

        val invocations = AtomicInteger()

        override suspend fun collect(into: MutableMap<String, MetricValue>) {
            invocations.incrementAndGet()
            action(into)
        }
    }

    @Test
    fun `demand reported before start is honoured from the first tick`() = runTest {
        val scheduler = SamplingScheduler(
            { config(fast = 100L, normal = 100L, slow = 5_000L, idle = 30_000L) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collector = RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        }
        scheduler.register(collector)
        // The overlay/service reports the HUD state before sampling starts.
        scheduler.setDemand(
            MetricDemand(outputActive = true, hudVisible = true, foregroundUi = true, screenOn = true),
        )
        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()
        val first = collector.invocations.get()
        assertEquals(1, first)

        advanceTimeBy(500L)
        runCurrent()
        assertTrue("expected the configured fast cadence, got ${collector.invocations.get()}", collector.invocations.get() > 2)
        scheduler.stop()
    }

    @Test
    fun `a failing collector cannot stop the others`() = runTest {
        val scheduler = SamplingScheduler({ config() }, dispatcher = StandardTestDispatcher(testScheduler))
        val good = RecordingCollector("good", SamplingTier.NORMAL) { into ->
            into["test.value"] = MetricValue.Number(42.0)
        }
        val bad = RecordingCollector("bad", SamplingTier.NORMAL) { throw IllegalStateException("boom") }
        val fast = RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        }
        scheduler.register(good)
        scheduler.register(bad)
        scheduler.register(fast)

        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()

        assertTrue(scheduler.snapshot.value.isAvailable("test.value"))
        assertTrue(scheduler.snapshot.value.isAvailable("test.fast"))
        assertEquals(1, bad.invocations.get())

        // The collector keeps being scheduled after its failure.
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(bad.invocations.get() > 1)
        assertTrue(good.invocations.get() > 1)
        scheduler.stop()
    }

    @Test
    fun `results from every tier are merged into one snapshot`() = runTest {
        val scheduler = SamplingScheduler({ config() }, dispatcher = StandardTestDispatcher(testScheduler))
        scheduler.register(RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        })
        scheduler.register(RecordingCollector("normal", SamplingTier.NORMAL) { into ->
            into["test.normal"] = MetricValue.Number(2.0)
        })
        scheduler.register(RecordingCollector("slow", SamplingTier.SLOW) { into ->
            into["test.slow"] = MetricValue.Number(3.0)
        })

        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()

        val snapshot = scheduler.snapshot.value
        assertEquals(1.0, snapshot.numberOrNull("test.fast")!!, 0.0001)
        assertEquals(2.0, snapshot.numberOrNull("test.normal")!!, 0.0001)
        assertEquals(3.0, snapshot.numberOrNull("test.slow")!!, 0.0001)
        scheduler.stop()
    }

    @Test
    fun `hidden demand drops the fast tier to the slow cadence`() = runTest {
        val scheduler = SamplingScheduler(
            { config(fast = 100L, normal = 100L, slow = 2_000L, idle = 10_000L) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collector = RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        }
        scheduler.register(collector)
        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()
        val immediately = collector.invocations.get()

        // Nothing is visible: the fast tier must idle at the slowest cadence.
        scheduler.setDemand(MetricDemand.Idle)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(immediately, collector.invocations.get())

        advanceTimeBy(12_000L)
        runCurrent()
        assertTrue(collector.invocations.get() > immediately)
        scheduler.stop()
    }

    @Test
    fun `showing the hud wakes a tier that was idling`() = runTest {
        val scheduler = SamplingScheduler(
            { config(fast = 100L, normal = 100L, slow = 5_000L, idle = 30_000L) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collector = RecordingCollector("fast", SamplingTier.FAST) { into ->
            into["test.fast"] = MetricValue.Number(1.0)
        }
        scheduler.register(collector)
        scheduler.setDemand(MetricDemand.Idle)
        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()
        val idling = collector.invocations.get()

        scheduler.setDemand(
            MetricDemand(outputActive = true, hudVisible = true, foregroundUi = true, screenOn = true),
        )
        runCurrent()
        assertTrue(collector.invocations.get() > idling)
        scheduler.stop()
    }

    @Test
    fun `a collector never runs concurrently with itself`() = runTest {
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        var overlapping = false
        val collector = RecordingCollector("slow", SamplingTier.NORMAL) { into ->
            val active = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, active) }
            delay(300L)
            if (concurrent.decrementAndGet() > 0) overlapping = true
            into["test.slow"] = MetricValue.Number(1.0)
        }
        val scheduler = SamplingScheduler(
            { config(fast = 100L, normal = 100L, slow = 100L, idle = 100L) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        scheduler.register(collector)
        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()
        scheduler.requestImmediate()
        scheduler.requestImmediate()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(false, overlapping)
        assertEquals(1, maxConcurrent.get())
        assertTrue(collector.invocations.get() > 1)
        scheduler.stop()
    }

    @Test
    fun `stop cancels every tier coroutine`() = runTest {
        val scheduler = SamplingScheduler({ config() }, dispatcher = StandardTestDispatcher(testScheduler))
        val collector = RecordingCollector("normal", SamplingTier.NORMAL) { into ->
            into["test.value"] = MetricValue.Number(1.0)
        }
        scheduler.register(collector)
        scheduler.start()
        advanceTimeBy(1L)
        runCurrent()
        val before = collector.invocations.get()

        scheduler.stop()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(before, collector.invocations.get())
    }
}
