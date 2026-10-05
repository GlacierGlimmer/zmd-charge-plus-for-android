package com.glacierglimmer.endfieldchargeplus.deepseek

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Peak / off-peak tests at fixed instants.
 *
 * The anchor dates are chosen so that `monday` really is a Monday (2026-01-05) and `saturday` the
 * following Saturday, which keeps the weekend rule test independent of the machine's calendar.
 */
class PeakWindowTest {

    private val monday: LocalDate = LocalDate.of(2026, 1, 5)
    private val saturday: LocalDate = monday.plusDays(5)

    private val defaultWindows = PeakWindow.parse("09:00-12:00;14:00-18:00")

    init {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
    }

    private fun at(day: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): ZonedDateTime =
        ZonedDateTime.of(day, LocalTime.of(hour, minute, second), PeakWindow.BEIJING)

    // ---- parsing ----------------------------------------------------------------------------

    @Test
    fun `parses the default specification`() {
        val windows = PeakWindow.parse("09:00-12:00;14:00-18:00")
        assertEquals(2, windows.size)
        assertEquals(LocalTime.of(9, 0), windows[0].start)
        assertEquals(LocalTime.of(12, 0), windows[0].end)
        assertEquals(LocalTime.of(14, 0), windows[1].start)
        assertEquals(LocalTime.of(18, 0), windows[1].end)
        assertFalse(windows[0].crossesMidnight)
    }

    @Test
    fun `malformed parts are ignored and valid windows survive`() {
        val windows = PeakWindow.parse("abc;09:00-12:00;25:00-26:00;12:00-12:00;14:00-18:00;09:00;")
        assertEquals(2, windows.size)
        assertEquals(LocalTime.of(9, 0), windows[0].start)
        assertEquals(LocalTime.of(14, 0), windows[1].start)
    }

    @Test
    fun `windows are sorted by start time`() {
        val windows = PeakWindow.parse("14:00-18:00;09:00-12:00")
        assertEquals(LocalTime.of(9, 0), windows[0].start)
        assertEquals(LocalTime.of(14, 0), windows[1].start)
    }

    @Test
    fun `overnight windows cross midnight and keep a positive duration`() {
        val window = PeakWindow.parse("22:00-02:00").single()
        assertTrue(window.crossesMidnight)
        assertEquals(4 * 60L, window.duration.toMinutes())
    }

    @Test
    fun `an empty or fully invalid specification yields no windows`() {
        assertTrue(PeakWindow.parse("").isEmpty())
        assertTrue(PeakWindow.parse("   ; ; ").isEmpty())
    }

    // ---- peak / off-peak --------------------------------------------------------------------

    @Test
    fun `inside a peak window is peak`() {
        val state = PeakWindow.currentPeriod(at(monday, 10, 0), defaultWindows)
        assertTrue(state.isPeak)
        assertEquals("peak", state.nameKey)
        assertEquals("PEAK", state.nameEn)
        assertEquals("高峰", state.nameZh)
        assertEquals(at(monday, 9, 0), state.segmentStart)
        assertEquals(at(monday, 12, 0), state.nextTransition)
    }

    @Test
    fun `between two windows is off peak`() {
        val state = PeakWindow.currentPeriod(at(monday, 13, 0), defaultWindows)
        assertFalse(state.isPeak)
        assertEquals("off_peak", state.nameKey)
        assertEquals("OFF-PEAK", state.nameEn)
        assertEquals("低谷", state.nameZh)
        assertEquals(at(monday, 12, 0), state.segmentStart)
        assertEquals(at(monday, 14, 0), state.nextTransition)
    }

    @Test
    fun `window start is inclusive and window end is exclusive`() {
        assertTrue(PeakWindow.currentPeriod(at(monday, 9, 0, 0), defaultWindows).isPeak)
        assertFalse(PeakWindow.currentPeriod(at(monday, 12, 0, 0), defaultWindows).isPeak)
        assertTrue(PeakWindow.currentPeriod(at(monday, 17, 59, 59), defaultWindows).isPeak)
        assertFalse(PeakWindow.currentPeriod(at(monday, 18, 0, 0), defaultWindows).isPeak)
    }

    @Test
    fun `weekend is always off peak`() {
        assertFalse(PeakWindow.currentPeriod(at(saturday, 10, 0), defaultWindows).isPeak)
        assertFalse(PeakWindow.currentPeriod(at(saturday, 15, 0), defaultWindows).isPeak)
        assertFalse(PeakWindow.currentPeriod(at(saturday.plusDays(1), 10, 0), defaultWindows).isPeak)
    }

    @Test
    fun `before the first window the previous weekday session end is the segment start`() {
        val state = PeakWindow.currentPeriod(at(monday, 8, 0), defaultWindows)
        assertFalse(state.isPeak)
        assertEquals(at(monday, 9, 0), state.nextTransition)
        assertEquals(at(monday.minusDays(3), 18, 0), state.segmentStart)
        assertEquals(3_600.0, state.remainingSeconds, 0.001)
    }

    @Test
    fun `after the last window the next weekday window is the transition`() {
        val state = PeakWindow.currentPeriod(at(monday, 20, 0), defaultWindows)
        assertFalse(state.isPeak)
        assertEquals(at(monday.plusDays(1), 9, 0), state.nextTransition)
        assertEquals(at(monday, 18, 0), state.segmentStart)
    }

    @Test
    fun `a weekend afternoon waits for monday morning`() {
        val state = PeakWindow.currentPeriod(at(saturday, 10, 0), defaultWindows)
        assertEquals(at(saturday.plusDays(2), 9, 0), state.nextTransition)
        assertEquals(at(monday.plusDays(4), 18, 0), state.segmentStart)
        assertEquals(169_200.0, state.remainingSeconds, 0.001)
    }

    @Test
    fun `an empty window list falls back to an off-peak period`() {
        val state = PeakWindow.currentPeriod(at(monday, 10, 0), emptyList())
        assertFalse(state.isPeak)
        assertEquals("off_peak", state.nameKey)
        assertEquals(3_600.0, state.remainingSeconds, 0.001)
        assertTrue(state.progressPercent in 0.0..100.0)
    }

    // ---- progress and text ------------------------------------------------------------------

    @Test
    fun `progress and remaining are measured inside the current window`() {
        val state = PeakWindow.currentPeriod(at(monday, 10, 30), defaultWindows)
        assertEquals(5_400.0, state.remainingSeconds, 0.001)
        assertEquals(50.0, state.progressPercent, 0.001)
        assertEquals("高峰时段剩余01:30:00", state.remainingTextZh)
        assertEquals("高峰已过50%", state.progressTextZh)
        assertEquals("Peak left 01:30:00", state.remainingTextEn)
        assertEquals("Peak 50%", state.progressTextEn)
        assertEquals(state.progressTextZh, state.progressText(english = false))
        assertEquals(state.remainingTextEn, state.remainingText(english = true))
    }

    @Test
    fun `a long off-peak stretch is rendered with days`() {
        val state = PeakWindow.currentPeriod(at(saturday, 10, 0), defaultWindows)
        assertEquals("低谷时段剩余1天 23:00:00", state.remainingTextZh)
        assertEquals("Off-peak left 1d 23:00:00", state.remainingTextEn)
    }

    @Test
    fun `a few minutes into a window gives a small progress`() {
        val state = PeakWindow.currentPeriod(at(monday, 9, 1), defaultWindows)
        assertEquals(1.0 / 180.0 * 100.0, state.progressPercent, 0.001)
        assertEquals("高峰已过1%", state.progressTextZh)
    }

    // ---- overnight windows ------------------------------------------------------------------

    @Test
    fun `an overnight window keeps the small hours of the next day in peak`() {
        val windows = PeakWindow.parse("22:00-02:00")
        val wednesday = monday.plusDays(2)

        val afterMidnight = PeakWindow.currentPeriod(at(wednesday, 1, 0), windows)
        assertTrue(afterMidnight.isPeak)
        assertEquals(at(wednesday.minusDays(1), 22, 0), afterMidnight.segmentStart)
        assertEquals(at(wednesday, 2, 0), afterMidnight.nextTransition)
        assertEquals(3_600.0, afterMidnight.remainingSeconds, 0.001)
        assertEquals(75.0, afterMidnight.progressPercent, 0.001)

        val lateEvening = PeakWindow.currentPeriod(at(wednesday, 23, 0), windows)
        assertTrue(lateEvening.isPeak)
        assertEquals(at(wednesday, 22, 0), lateEvening.segmentStart)
        assertEquals(at(wednesday.plusDays(1), 2, 0), lateEvening.nextTransition)
        assertEquals(10_800.0, lateEvening.remainingSeconds, 0.001)

        assertFalse(PeakWindow.currentPeriod(at(wednesday, 2, 0), windows).isPeak)
        assertFalse(PeakWindow.currentPeriod(at(wednesday, 12, 0), windows).isPeak)
    }

    @Test
    fun `an overnight window anchored on friday still covers saturday morning`() {
        val windows = PeakWindow.parse("22:00-02:00")
        val state = PeakWindow.currentPeriod(at(saturday, 1, 0), windows)
        assertTrue(state.isPeak)
        assertEquals(at(saturday.minusDays(1), 22, 0), state.segmentStart)
    }

    @Test
    fun `now in another zone is converted to beijing time`() {
        val utcInstant = at(monday, 10, 0).withZoneSameInstant(java.time.ZoneId.of("UTC"))
        val state = PeakWindow.currentPeriod(utcInstant, defaultWindows)
        assertTrue(state.isPeak)
        assertEquals("peak", state.nameKey)
    }
}
