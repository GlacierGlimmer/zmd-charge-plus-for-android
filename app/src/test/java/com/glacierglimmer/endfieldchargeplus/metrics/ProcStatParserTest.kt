package com.glacierglimmer.endfieldchargeplus.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/proc/stat` parsing and two-sample CPU load arithmetic.
 *
 * The conventions under test are the Linux/ECP ones: idle = idle + iowait, guest fields excluded,
 * and no rate from a single sample or from a counter reset.
 */
class ProcStatParserTest {

    private val sample = """
        cpu  1000 200 300 8000 400 50 60 0 0 0
        cpu0 500 100 150 4000 200 25 30 0 0 0
        cpu1 500 100 150 4000 200 25 30 0 0 0
        intr 12345 0 0 0
        ctxt 67890
        btime 1700000000
        processes 42
    """.trimIndent()

    @Test
    fun `aggregate line uses eight fields and idle plus iowait`() {
        val snapshot = ProcStatParser.parse(sample)
        val total = snapshot.totalCpu

        assertEquals(1000L + 200 + 300 + 8000 + 400 + 50 + 60, total?.total)
        assertEquals(8000L + 400, total?.idle)
        assertEquals(12345L, snapshot.interrupts)
        assertEquals(67890L, snapshot.contextSwitches)
    }

    @Test
    fun `guest columns are excluded from the total`() {
        val withGuest = ProcStatParser.parse("cpu  10 10 10 10 0 0 0 0 999999 999999\n")
        val withoutGuest = ProcStatParser.parse("cpu  10 10 10 10 0 0 0 0\n")
        assertEquals(withoutGuest.totalCpu?.total, withGuest.totalCpu?.total)
    }

    @Test
    fun `per-core lines map to their index`() {
        val snapshot = ProcStatParser.parse(sample)

        assertEquals(setOf(0, 1), snapshot.cores.keys)
        assertEquals(500L + 100 + 150 + 4000 + 200 + 25 + 30, snapshot.cores[0]?.total)
        assertEquals(4000L + 200, snapshot.cores[0]?.idle)
    }

    @Test
    fun `a malformed line is skipped instead of producing a fake value`() {
        val snapshot = ProcStatParser.parse("cpu  not numbers\ncpu0 1 2 3 4\n")

        assertNull(snapshot.totalCpu)
        assertEquals(10L, snapshot.cores[0]?.total)
    }

    @Test
    fun `usage is busy over total and is clamped to 0-100`() {
        val previous = CpuTicks(total = 1_000L, idle = 900L)
        val current = CpuTicks(total = 1_100L, idle = 940L)

        // 100 ticks elapsed, 60 of them busy.
        assertEquals(60.0, CpuUsageMath.usagePercent(previous, current)!!, 0.0001)
    }

    @Test
    fun `an unparsable reading exposes no aggregate ticks`() {
        val snapshot = ProcStatParser.parse("cpu  \nintr 1\n")
        assertNull(snapshot.totalCpu)
    }

    @Test
    fun `a counter reset yields null instead of a bogus percentage`() {
        val previous = CpuTicks(total = 5_000L, idle = 4_000L)
        val afterReboot = CpuTicks(total = 100L, idle = 90L)

        assertNull(CpuUsageMath.usagePercent(previous, afterReboot))
    }

    @Test
    fun `zero elapsed ticks yield null`() {
        val ticks = CpuTicks(total = 1_000L, idle = 900L)
        assertNull(CpuUsageMath.usagePercent(ticks, ticks))
    }
}
