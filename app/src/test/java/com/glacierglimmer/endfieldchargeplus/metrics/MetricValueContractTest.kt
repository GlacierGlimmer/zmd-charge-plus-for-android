package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "no fabricated data" contract at the value level: unreadable metrics are `Unavailable` with a
 * reason, never a `0`, and the snapshot helpers used by the collectors keep that promise.
 */
class MetricValueContractTest {

    @Test
    fun `an unavailable value is never available and carries its reason`() {
        val values = mutableMapOf<String, MetricValue>()
        values.putUnavailable("cpu.temperature_c", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, "no CPU thermal zone")

        val value = values.getValue("cpu.temperature_c")
        assertFalse(value.isAvailable)
        assertTrue(value is MetricValue.Unavailable)
        assertEquals(UnavailableReason.NOT_AVAILABLE_ON_DEVICE, (value as MetricValue.Unavailable).reason)
        assertEquals("no CPU thermal zone", value.detail)
    }

    @Test
    fun `a missing number becomes NoData instead of zero`() {
        val values = mutableMapOf<String, MetricValue>()
        values.putNumber("cpu.frequency_mhz", null)
        values.putNumber("cpu.usage", Double.NaN)
        values.putNumber("memory.total_bytes", 1_024.0)

        assertEquals(UnavailableReason.NO_DATA, (values["cpu.frequency_mhz"] as MetricValue.Unavailable).reason)
        assertEquals(UnavailableReason.NO_DATA, (values["cpu.usage"] as MetricValue.Unavailable).reason)
        assertEquals(1_024.0, (values["memory.total_bytes"] as MetricValue.Number).value, 0.0001)
    }

    @Test
    fun `blank text becomes NoData instead of an empty string`() {
        val values = mutableMapOf<String, MetricValue>()
        values.putText("cpu.model", "   ")
        values.putText("cpu.abi", "arm64-v8a")

        assertTrue(values["cpu.model"] is MetricValue.Unavailable)
        assertEquals("arm64-v8a", (values["cpu.abi"] as MetricValue.Text).value)
    }

    @Test
    fun `snapshot lookup never turns an unavailable metric into a number`() {
        val snapshot = MetricSnapshot(
            values = mapOf(
                "gpu.usage" to MetricValue.Number(0.0),
                "gpu.memory_total_bytes" to MetricValue.Unavailable(UnavailableReason.NOT_SUPPORTED, "no API"),
            ),
            timestampMs = 1L,
        )

        assertEquals(0.0, snapshot.numberOrNull("gpu.usage")!!, 0.0001)
        assertNull(snapshot.numberOrNull("gpu.memory_total_bytes"))
        assertFalse(snapshot.isAvailable("gpu.memory_total_bytes"))
        assertEquals(UnavailableReason.NOT_SUPPORTED, snapshot.unavailableReason("gpu.memory_total_bytes"))
        assertNull(snapshot.numberOrNull("missing.variable"))
    }

    @Test
    fun `every unavailable reason exists for a distinct platform condition`() {
        val reasons = UnavailableReason.entries
        assertTrue(reasons.contains(UnavailableReason.NOT_SUPPORTED))
        assertTrue(reasons.contains(UnavailableReason.PERMISSION_REQUIRED))
        assertTrue(reasons.contains(UnavailableReason.NOT_AVAILABLE_ON_DEVICE))
        assertTrue(reasons.contains(UnavailableReason.NO_DATA))
        assertTrue(reasons.contains(UnavailableReason.DISABLED))
    }
}
