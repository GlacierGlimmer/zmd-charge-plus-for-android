package com.glacierglimmer.endfieldchargeplus.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `/proc/meminfo` parsing: only real fields become values, missing fields stay `null`. */
class MemInfoParserTest {

    @Test
    fun `parses all supported fields and converts kB to bytes`() {
        val parsed = MemInfoParser.parse(
            """
            MemTotal:       16301234 kB
            MemFree:          512000 kB
            MemAvailable:    8192000 kB
            Buffers:          128000 kB
            Cached:          2048000 kB
            SwapCached:        10000 kB
            SwapTotal:       4194304 kB
            SwapFree:        4000000 kB
            """.trimIndent(),
        )

        assertEquals(16_301_234L * 1024L, parsed.totalBytes)
        assertEquals(8_192_000L * 1024L, parsed.availableBytes)
        assertEquals(512_000L * 1024L, parsed.freeBytes)
        assertEquals(2_048_000L * 1024L, parsed.cachedBytes)
        assertEquals(4_194_304L * 1024L, parsed.swapTotalBytes)
        assertEquals(194_304L * 1024L, parsed.swapUsedBytes)
    }

    @Test
    fun `missing fields are null and never default to zero`() {
        val parsed = MemInfoParser.parse("MemTotal: 1000 kB\nMemAvailable: 400 kB\n")

        assertEquals(1_024_000L, parsed.totalBytes)
        assertNull(parsed.cachedBytes)
        assertNull(parsed.freeBytes)
        assertNull(parsed.swapTotalBytes)
        assertNull(parsed.swapUsedBytes)
    }

    @Test
    fun `a real zero swap total is preserved as zero`() {
        val parsed = MemInfoParser.parse("MemTotal: 1000 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n")

        assertEquals(0L, parsed.swapTotalBytes)
        assertEquals(0L, parsed.swapUsedBytes)
    }

    @Test
    fun `malformed lines are ignored`() {
        val parsed = MemInfoParser.parse(
            """
            nonsense
            MemTotal: notanumber kB
            MemAvailable: 12345 kB
            """.trimIndent(),
        )

        assertNull(parsed.totalBytes)
        assertEquals(12_345L * 1024L, parsed.availableBytes)
    }

    @Test
    fun `swap used is clamped when SwapFree exceeds SwapTotal`() {
        val parsed = MemInfoParser.parse("SwapTotal: 100 kB\nSwapFree: 500 kB\n")
        assertEquals(0L, parsed.swapUsedBytes)
        assertNotNull(parsed.swapFreeBytes)
    }
}
