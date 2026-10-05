package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkDisplayUnit
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkPercentMode
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TrafficStats delta math and the profile-driven network presentation. */
class NetworkMathTest {

    @Test
    fun `rate is bytes over the real elapsed interval`() {
        // 1_000_000 bytes in 2 seconds.
        assertEquals(500_000.0, TrafficRateMath.rateBps(0L, 1_000_000L, 2_000L)!!, 0.0001)
    }

    @Test
    fun `a single sample produces no rate`() {
        assertNull(TrafficRateMath.rateBps(1_000L, 1_000L, 0L))
    }

    @Test
    fun `a counter reset produces no rate`() {
        assertNull(TrafficRateMath.rateBps(9_000L, 100L, 1_000L))
    }

    @Test
    fun `unsupported counters produce no rate`() {
        assertNull(TrafficRateMath.rateBps(-1L, 100L, 1_000L))
    }

    @Test
    fun `auto byte units are decimal SI`() {
        assertEquals("0.5 KB/s", NetworkFormat.autoBytes(500.0))
        assertEquals("999.9 KB/s", NetworkFormat.autoBytes(999_900.0))
        assertEquals("1.0 MB/s", NetworkFormat.autoBytes(1_000_000.0))
        assertEquals("12.3 MB/s", NetworkFormat.autoBytes(12_345_678.0))
    }

    @Test
    fun `mbps uses decimal megabits`() {
        assertEquals("8.0 Mbps", NetworkFormat.mbps(1_000_000.0))
        assertEquals("0.0 Mbps", NetworkFormat.mbps(0.0))
    }

    @Test
    fun `display unit selects the renderer`() {
        assertEquals("8.0 Mbps", NetworkFormat.display(1_000_000.0, NetworkDisplayUnit.MBPS))
        assertEquals("1.0 MB/s", NetworkFormat.display(1_000_000.0, NetworkDisplayUnit.AUTO_BYTES))
    }

    @Test
    fun `reference units convert to bytes per second`() {
        // 500 Mbps = 62.5 MB/s (decimal), matching ECP's ToBytesPerSecond.
        assertEquals(62_500_000.0, NetworkFormat.referenceBytesPerSecond(500.0, "Mbps")!!, 0.001)
        assertEquals(1_000.0, NetworkFormat.referenceBytesPerSecond(1.0, "KB/s")!!, 0.001)
        assertEquals(1_000_000.0, NetworkFormat.referenceBytesPerSecond(1.0, "MB/s")!!, 0.001)
        // ECP's documented fallback for an unknown unit behaves like MB/s.
        assertEquals(2_000_000.0, NetworkFormat.referenceBytesPerSecond(2.0, "weird")!!, 0.001)
    }

    @Test
    fun `percent mode picks the measured direction`() {
        assertEquals(300.0, NetworkFormat.measuredBytesPerSecond(NetworkPercentMode.TOTAL, 100.0, 200.0)!!, 0.001)
        assertEquals(100.0, NetworkFormat.measuredBytesPerSecond(NetworkPercentMode.DOWNLOAD, 100.0, 200.0)!!, 0.001)
        assertEquals(200.0, NetworkFormat.measuredBytesPerSecond(NetworkPercentMode.UPLOAD, 100.0, 200.0)!!, 0.001)
        assertEquals(200.0, NetworkFormat.measuredBytesPerSecond(NetworkPercentMode.MAX, 100.0, 200.0)!!, 0.001)
        assertNull(NetworkFormat.measuredBytesPerSecond(NetworkPercentMode.TOTAL, null, null))
    }

    @Test
    fun `percent is clamped and a zero reference never divides`() {
        assertEquals(50.0, NetworkFormat.percent(500.0, 1_000.0), 0.0001)
        assertEquals(100.0, NetworkFormat.percent(5_000.0, 1_000.0), 0.0001)
        assertEquals(0.0, NetworkFormat.percent(500.0, 0.0), 0.0001)
    }

    @Test
    fun `percent text carries the download and upload arrows`() {
        assertEquals("42%", NetworkFormat.percentText(41.5, NetworkPercentMode.TOTAL))
        assertEquals("42%", NetworkFormat.percentText(41.5, NetworkPercentMode.MAX))
        assertEquals("↓ 42%", NetworkFormat.percentText(41.6, NetworkPercentMode.DOWNLOAD))
        assertEquals("↑ 42%", NetworkFormat.percentText(41.6, NetworkPercentMode.UPLOAD))
    }

    @Test
    fun `unavailable rates propagate as unavailable instead of zero`() {
        val values = mutableMapOf<String, MetricValue>()
        values.putUnavailable("network.download_bps", UnavailableReason.NO_DATA, "first sample")

        val value = values.getValue("network.download_bps")
        assertTrue(value is MetricValue.Unavailable)
        assertEquals(UnavailableReason.NO_DATA, (value as MetricValue.Unavailable).reason)
        assertEquals(false, value.isAvailable)
        // A would-be zero must never be written for an unreadable rate.
        assertNull(values["network.download_bps"] as? MetricValue.Number)
    }
}
