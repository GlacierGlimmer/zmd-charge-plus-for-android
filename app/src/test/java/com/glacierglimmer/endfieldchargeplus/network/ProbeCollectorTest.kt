package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe bookkeeping tests that do not need a device: the localized status keys and the shape of the
 * rolling [ProbeStatistics] window the collector publishes.
 */
class ProbeCollectorTest {

    @Test
    fun `every stable status key has a Chinese and an English label`() {
        val keys = listOf(
            ProbeCollector.STATUS_IDLE,
            ProbeCollector.STATUS_PROBING,
            ProbeCollector.STATUS_OK,
            ProbeCollector.STATUS_TIMEOUT,
            ProbeCollector.STATUS_DNS_ERROR,
            ProbeCollector.STATUS_UNREACHABLE,
            ProbeCollector.STATUS_UNSUPPORTED,
            ProbeCollector.STATUS_DISABLED,
        )
        for (key in keys) {
            val zh = ProbeCollector.statusText(key, english = false)
            val en = ProbeCollector.statusText(key, english = true)
            assertTrue("missing Chinese label for $key", zh.isNotBlank())
            assertTrue("missing English label for $key", en.isNotBlank())
        }
    }

    @Test
    fun `status text is localized and unknown keys degrade to the raw key`() {
        assertEquals("超时", ProbeCollector.statusText(ProbeCollector.STATUS_TIMEOUT, english = false))
        assertEquals("Timed out", ProbeCollector.statusText(ProbeCollector.STATUS_TIMEOUT, english = true))
        assertEquals("custom_status", ProbeCollector.statusText("custom_status", english = false))
    }

    @Test
    fun `empty window reports zero sent without a fabricated latency`() {
        val statistics = ProbeStatistics(12)
        assertEquals(0, statistics.sent())
        assertEquals(0, statistics.received())
        assertEquals(null, statistics.averageLatencyMs())
        assertEquals(null, statistics.jitterMs())
        assertEquals(null, statistics.last())
    }

    @Test
    fun `window computes loss jitter and drops skipped results`() {
        val statistics = ProbeStatistics(3)
        statistics.record(result(success = true, latency = 10.0))
        statistics.record(result(success = true, latency = 20.0))
        statistics.record(result(success = false))
        assertEquals(3, statistics.sent())
        assertEquals(2, statistics.received())
        assertEquals(100.0 / 3.0, statistics.lossPercent(), 1e-9)
        assertEquals(15.0, statistics.averageLatencyMs()!!, 1e-9)
        assertEquals(10.0, statistics.jitterMs()!!, 1e-9)

        statistics.record(result(success = true, latency = 30.0))
        assertEquals(3, statistics.sent())

        statistics.record(ProbeResult(0L, "host", ProbeProtocol.TCP, false, null, skipped = true))
        assertEquals(3, statistics.sent())
    }

    @Test
    fun `skipped results keep the published value available`() {
        val skipped = ProbeResult(1L, "host", ProbeProtocol.TCP, false, null, skipped = true)
        assertTrue(skipped.skipped)
        assertTrue(!skipped.success)
    }

    private fun result(success: Boolean, latency: Double? = null): ProbeResult = ProbeResult(
        timestampMs = 1L,
        target = "host",
        protocol = ProbeProtocol.TCP,
        success = success,
        latencyMs = latency,
    )

    @Test
    fun `probe variable catalog keeps the status text key`() {
        val names = com.glacierglimmer.endfieldchargeplus.core.metrics.Variables.PROBE_ALL
        assertTrue(names.contains(com.glacierglimmer.endfieldchargeplus.core.metrics.Variables.PROBE_LATENCY_MS))
        assertTrue(names.contains(com.glacierglimmer.endfieldchargeplus.core.metrics.Variables.PROBE_STATUS_TEXT))
    }
}
