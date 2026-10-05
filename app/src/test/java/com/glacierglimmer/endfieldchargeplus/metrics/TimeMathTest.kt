package com.glacierglimmer.endfieldchargeplus.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Clock arithmetic: day progress and the daily target mode, evaluated at fixed instants. */
class TimeMathTest {

    @Test
    fun `day progress is the elapsed fraction of the local day`() {
        assertEquals(0.0, TimeMath.dayProgressPercent(LocalTime.MIDNIGHT), 0.0001)
        assertEquals(50.0, TimeMath.dayProgressPercent(LocalTime.NOON), 0.0001)
        // 06:00:00 = 21600 s of 86400 s.
        assertEquals(25.0, TimeMath.dayProgressPercent(LocalTime.of(6, 0, 0)), 0.0001)
    }

    @Test
    fun `target parsing accepts HH mm ss and HH mm and rejects garbage`() {
        assertEquals(LocalTime.of(10, 0, 0), TimeMath.parseTarget("10:00:00"))
        assertEquals(LocalTime.of(9, 30, 0), TimeMath.parseTarget("09:30"))
        assertEquals(LocalTime.of(23, 59, 59), TimeMath.parseTarget("23:59:59"))
        assertNull(TimeMath.parseTarget("25:00:00"))
        assertNull(TimeMath.parseTarget(""))
        assertNull(TimeMath.parseTarget("tomorrow"))
        // ECP's fallback target.
        assertEquals(LocalTime.of(10, 0, 0), TimeMath.resolvedTarget("nonsense"))
    }

    @Test
    fun `before the target the countdown points at today`() {
        val now = LocalDateTime.of(2026, 10, 5, 8, 0, 0)

        val metrics = TimeMath.targetMetrics(now, "10:00:00")

        assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0, 0), metrics.nextOccurrence)
        assertEquals(2 * 3600L, metrics.remainingSeconds)
        // 2 h of 24 h = 8.333 % remaining, so the target progress is the complement.
        assertEquals(100.0 / 12.0, metrics.remainingPercent, 0.01)
        assertEquals(100.0 - 100.0 / 12.0, metrics.progressPercent, 0.01)
    }

    @Test
    fun `at the exact target the countdown rolls to tomorrow`() {
        val now = LocalDateTime.of(2026, 10, 5, 10, 0, 0)

        val metrics = TimeMath.targetMetrics(now, "10:00:00")

        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 0, 0), metrics.nextOccurrence)
        assertEquals(86_400L, metrics.remainingSeconds)
        assertEquals(100.0, metrics.remainingPercent, 0.0001)
        assertEquals(0.0, metrics.progressPercent, 0.0001)
    }

    @Test
    fun `after the target the countdown points at tomorrow`() {
        val now = LocalDateTime.of(2026, 10, 5, 22, 30, 0)

        val metrics = TimeMath.targetMetrics(now, "10:00:00")

        assertEquals(LocalDateTime.of(2026, 10, 6, 10, 0, 0), metrics.nextOccurrence)
        // 11 h 30 min until the next occurrence.
        assertEquals(11 * 3600L + 30 * 60L, metrics.remainingSeconds)
        assertEquals(47.9166, metrics.remainingPercent, 0.01)
    }

    @Test
    fun `remaining text rounds away from zero in both languages`() {
        assertEquals("剩余8%", TimeMath.remainingText(8.333, chinese = true))
        assertEquals("Left 8%", TimeMath.remainingText(8.333, chinese = false))
        assertEquals("剩余50%", TimeMath.targetMetrics(
            LocalDateTime.of(2026, 10, 5, 22, 0, 0),
            "10:00:00",
            chinese = true,
        ).remainingText)
    }

    @Test
    fun `day status text is the rounded percentage`() {
        assertEquals("25%", TimeMath.percentText(25.0))
        assertEquals("50%", TimeMath.percentText(49.6))
    }

    @Test
    fun `clock and date formatting follow the ECP wire format`() {
        assertEquals("09:05:07", TimeMath.formatClock(LocalTime.of(9, 5, 7)))
        assertEquals("2026-10-05", TimeMath.formatDate(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `weekday text is localized`() {
        assertEquals("Monday", TimeMath.weekdayText(DayOfWeek.MONDAY, chinese = false))
        assertEquals("星期一", TimeMath.weekdayText(DayOfWeek.MONDAY, chinese = true))
    }
}
