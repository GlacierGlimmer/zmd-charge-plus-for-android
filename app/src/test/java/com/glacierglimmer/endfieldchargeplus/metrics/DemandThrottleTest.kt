package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demand throttling and the aligned tick grid.
 *
 * The scheduler is the only clock in the data layer, so its cadence rules are the power contract:
 * hidden HUD => at least the slow cadence, screen off => at least `screenOffRefreshMs`, nothing
 * active at all => the slowest cadence.
 */
class DemandThrottleTest {

    private val settings = AndroidSettings(
        fastRefreshMs = 500L,
        normalRefreshMs = 1_000L,
        slowRefreshMs = 5_000L,
        idleRefreshMs = 30_000L,
        throttleWhenHidden = true,
        throttleWhenScreenOff = true,
        screenOffRefreshMs = 15_000L,
    )

    private val fullyActive = MetricDemand(
        outputActive = true,
        hudVisible = true,
        foregroundUi = true,
        screenOn = true,
    )

    @Test
    fun `fully active keeps the configured cadence per tier`() {
        assertEquals(500L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, fullyActive))
        assertEquals(1_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.NORMAL, settings, fullyActive))
        assertEquals(5_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.SLOW, settings, fullyActive))
        assertEquals(30_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.IDLE, settings, fullyActive))
    }

    @Test
    fun `hidden hud is throttled to at least the slow cadence`() {
        val hidden = fullyActive.copy(hudVisible = false)

        assertEquals(5_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, hidden))
        assertEquals(5_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.NORMAL, settings, hidden))
        assertEquals(5_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.SLOW, settings, hidden))
    }

    @Test
    fun `screen off uses the screen off cadence`() {
        val screenOff = fullyActive.copy(screenOn = false)

        assertEquals(15_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, screenOff))
        assertEquals(15_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.NORMAL, settings, screenOff))
        assertEquals(15_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.SLOW, settings, screenOff))
        // Never faster than the idle tier's own cadence.
        assertEquals(30_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.IDLE, settings, screenOff))
    }

    @Test
    fun `nothing active idles at the slowest cadence`() {
        val interval = DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, MetricDemand.Idle)
        assertEquals(30_000L, interval)
    }

    @Test
    fun `the slowest applicable throttle always wins`() {
        val everything = MetricDemand(
            outputActive = false,
            hudVisible = false,
            foregroundUi = false,
            screenOn = false,
        )

        // hidden (5 s), screen off (15 s) and fully idle (30 s) apply together.
        assertEquals(30_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, everything))
    }

    @Test
    fun `user pause forces the slowest cadence even when visible`() {
        val paused = fullyActive.copy(userPaused = true)
        assertEquals(30_000L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, settings, paused))
    }

    @Test
    fun `throttling can be switched off in the settings`() {
        val permissive = settings.copy(throttleWhenHidden = false, throttleWhenScreenOff = false)
        val hiddenAndOff = fullyActive.copy(hudVisible = false, screenOn = false, outputActive = true, foregroundUi = true)

        assertEquals(500L, DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, permissive, hiddenAndOff))
    }

    @Test
    fun `a zero interval can never busy loop`() {
        val broken = settings.copy(fastRefreshMs = 0L, normalRefreshMs = -10L)
        assertTrue(DemandThrottle.effectiveIntervalMs(SamplingTier.FAST, broken, fullyActive) >= TierTick.MIN_INTERVAL_MS)
    }

    @Test
    fun `ticks stay on the aligned grid`() {
        var now = 0L
        val tick = TierTick { now }

        assertEquals(1_000L, tick.delayUntilNext(1_000L))
        now += 1_000L
        tick.consume(1_000L)
        assertEquals(1_000L, tick.delayUntilNext(1_000L))
        // A late tick still targets the next grid point instead of shifting the grid.
        now += 1_250L
        tick.consume(1_000L)
        assertEquals(750L, tick.delayUntilNext(1_000L))
    }

    @Test
    fun `peeking at a tick does not consume it`() {
        var now = 0L
        val tick = TierTick { now }

        assertEquals(30_000L, tick.delayUntilNext(30_000L))
        // An immediate wake-up pass happens here; the scheduled tick must survive.
        now += 100L
        assertEquals(29_900L, tick.delayUntilNext(30_000L))
    }

    @Test
    fun `an interval change re-bases the grid immediately`() {
        var now = 0L
        val tick = TierTick { now }

        assertEquals(30_000L, tick.delayUntilNext(30_000L))
        now += 500L
        // Demand became active: the tier must not wait for the old 30 s tick.
        assertEquals(500L, tick.delayUntilNext(500L))
    }
}
