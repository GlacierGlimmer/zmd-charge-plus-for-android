package com.glacierglimmer.endfieldchargeplus.deepseek

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * DeepSeek peak / off-peak (高峰 / 低谷) arithmetic.
 *
 * Rules ported from the desktop editions:
 *  * every window is expressed in Beijing time (`Asia/Shanghai`, UTC+08:00), which never observes
 *    DST, so all arithmetic is exact [ZonedDateTime] arithmetic with no offset juggling;
 *  * windows are `HH:mm-HH:mm` pairs separated by `;` (ECP default `09:00-12:00;14:00-18:00`);
 *  * weekends are always off-peak — a window only exists when it is anchored on a weekday;
 *  * malformed parts are ignored instead of failing the whole specification.
 *
 * One deliberate extension over the desktop parser: a window whose end is earlier than its start
 * (`22:00-02:00`) is an **overnight** window. It runs from its weekday anchor through midnight, so
 * the small hours of the following day are still peak. A zero-length window (`12:00-12:00`) is
 * ignored as malformed, exactly like ECP ignoring `b <= a`.
 */
object PeakWindow {

    /** The one time zone the peak rules are defined in. `Asia/Shanghai` has no DST. */
    val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SEARCH_DAYS = 9L

    /** One parsed peak window. [end] earlier than [start] means the window crosses midnight. */
    data class Window(val start: LocalTime, val end: LocalTime) {

        /** True when this window spans midnight. */
        val crossesMidnight: Boolean get() = end.isBefore(start)

        /** Wall-clock length of the window, never negative. */
        val duration: Duration
            get() = if (crossesMidnight) {
                Duration.between(start, end).plusDays(1)
            } else {
                Duration.between(start, end)
            }
    }

    /** The current peak/off-peak period, its progress and the localized display strings. */
    data class PeriodState(
        val isPeak: Boolean,
        /** Stable machine key: `peak` or `off_peak`. */
        val nameKey: String,
        /** Chinese period name: `高峰` / `低谷`. */
        val nameZh: String,
        /** English period name: `PEAK` / `OFF-PEAK` (the desktop `deepseek.period.name`). */
        val nameEn: String,
        val remainingSeconds: Double,
        val progressPercent: Double,
        val remainingTextZh: String,
        val remainingTextEn: String,
        val progressTextZh: String,
        val progressTextEn: String,
        /** Next boundary (window start or end) strictly after now, in Beijing time. */
        val nextTransition: ZonedDateTime,
        /** Start of the current period, in Beijing time. */
        val segmentStart: ZonedDateTime,
    ) {
        /** Localized "remaining" text; [english] selects the ECP English wording. */
        fun remainingText(english: Boolean): String = if (english) remainingTextEn else remainingTextZh

        /** Localized "progress" text; [english] selects the ECP English wording. */
        fun progressText(english: Boolean): String = if (english) progressTextEn else progressTextZh
    }

    /**
     * Parses a `09:00-12:00;14:00-18:00` specification. Invalid parts (wrong shape, out-of-range
     * clock values, zero length) are dropped; the result is sorted by start time.
     */
    fun parse(spec: String): List<Window> {
        val windows = mutableListOf<Window>()
        for (part in spec.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val pieces = trimmed.split('-')
            if (pieces.size != 2) continue
            val start = parseTime(pieces[0]) ?: continue
            val end = parseTime(pieces[1]) ?: continue
            if (start == end) continue
            windows += Window(start, end)
        }
        return windows.sortedBy { it.start }
    }

    /**
     * Computes the period that contains [now]. [now] may be in any zone; it is converted to
     * [BEIJING] first, so callers can pass their own clock directly.
     */
    fun currentPeriod(now: ZonedDateTime, windows: List<Window>): PeriodState {
        val beijing = now.withZoneSameInstant(BEIJING)
        val intervals = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()
        val boundaries = mutableListOf<ZonedDateTime>()

        for (offset in -SEARCH_DAYS..SEARCH_DAYS) {
            val day = beijing.toLocalDate().plusDays(offset)
            if (isWeekend(day)) continue
            for (window in windows) {
                val start = ZonedDateTime.of(day, window.start, BEIJING)
                val end = start.plus(window.duration)
                intervals += start to end
                boundaries += start
                boundaries += end
            }
        }

        if (intervals.isEmpty()) {
            return buildState(
                beijing = beijing,
                isPeak = false,
                segmentStart = beijing.minusHours(1),
                nextTransition = beijing.plusHours(1),
            )
        }

        val active = intervals.firstOrNull { (start, end) ->
            !beijing.isBefore(start) && beijing.isBefore(end)
        }
        val next = boundaries.filter { it.isAfter(beijing) }.minOrNull() ?: beijing.plusHours(1)
        val segmentStart = active?.first
            ?: boundaries.filter { !it.isAfter(beijing) }.maxOrNull()
            ?: beijing.minusHours(1)

        return buildState(
            beijing = beijing,
            isPeak = active != null,
            segmentStart = segmentStart,
            nextTransition = next,
        )
    }

    private fun buildState(
        beijing: ZonedDateTime,
        isPeak: Boolean,
        segmentStart: ZonedDateTime,
        nextTransition: ZonedDateTime,
    ): PeriodState {
        val remaining = millisBetween(beijing, nextTransition).coerceAtLeast(0.0)
        val total = millisBetween(segmentStart, nextTransition)
        val progress = if (total <= 0.0) {
            0.0
        } else {
            (millisBetween(segmentStart, beijing) / total * 100.0).coerceIn(0.0, 100.0)
        }

        val nameZh = if (isPeak) "高峰" else "低谷"
        val nameEn = if (isPeak) "PEAK" else "OFF-PEAK"
        val remainingSeconds = remaining / 1_000.0
        val duration = formatDuration(remainingSeconds, english = false)
        val durationEn = formatDuration(remainingSeconds, english = true)
        val percent = progress.roundToLong()
        val peakEn = if (isPeak) "Peak" else "Off-peak"

        return PeriodState(
            isPeak = isPeak,
            nameKey = if (isPeak) "peak" else "off_peak",
            nameZh = nameZh,
            nameEn = nameEn,
            remainingSeconds = remainingSeconds,
            progressPercent = progress,
            remainingTextZh = "${nameZh}时段剩余$duration",
            remainingTextEn = "$peakEn left $durationEn",
            progressTextZh = "${nameZh}已过$percent%",
            progressTextEn = "$peakEn $percent%",
            nextTransition = nextTransition,
            segmentStart = segmentStart,
        )
    }

    private fun millisBetween(from: ZonedDateTime, to: ZonedDateTime): Double =
        Duration.between(from, to).toMillis().toDouble()

    private fun isWeekend(day: LocalDate): Boolean =
        day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY

    /** `HH:mm` or `HH:mm:ss`; anything out of range yields `null`. */
    private fun parseTime(text: String): LocalTime? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return LocalTime.of(hour, minute, second)
    }

    /** ECP `FormatDurationLong`: `HH:MM:SS`, or `Nd HH:MM:SS` / `N天 HH:MM:SS` beyond a day. */
    private fun formatDuration(seconds: Double, english: Boolean): String {
        val safe = floor(seconds).toLong().coerceAtLeast(0L)
        val days = safe / SECONDS_PER_DAY
        val hours = (safe % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (safe % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val secs = safe % SECONDS_PER_MINUTE
        return if (days >= 1) {
            val unit = if (english) "d" else "天"
            "$days$unit ${pad(hours)}:${pad(minutes)}:${pad(secs)}"
        } else {
            "${pad(safe / SECONDS_PER_HOUR)}:${pad(minutes)}:${pad(secs)}"
        }
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')
}
