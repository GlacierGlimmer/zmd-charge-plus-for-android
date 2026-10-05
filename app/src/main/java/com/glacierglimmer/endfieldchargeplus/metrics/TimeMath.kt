package com.glacierglimmer.endfieldchargeplus.metrics

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.round

/** Derived values for the daily target-time mode (`time.target.*`, `time.display.*`). */
internal data class TargetMetrics(
    val target: LocalTime,
    val nextOccurrence: LocalDateTime,
    val remainingSeconds: Long,
    val remainingPercent: Double,
    val progressPercent: Double,
    val remainingText: String,
)

/**
 * Clock/calendar arithmetic ported from `HudProfileRenderer` of the Windows edition.
 *
 * All functions are pure so they can be verified at fixed clocks without a device, and the
 * semantics match the desktop editions exactly: `day_progress` is the elapsed fraction of the
 * local day, the target mode counts down to the *next* daily occurrence, and
 * `time.target.progress = 100 - remaining_percent`.
 */
internal object TimeMath {

    const val SECONDS_PER_DAY = 86_400.0

    private const val MILLIS_PER_DAY = 86_400_000.0

    private val TARGET_PATTERNS = listOf("HH:mm:ss", "H:mm:ss", "HH:mm", "H:mm")

    /** `HudProfile.TimeTarget` default when the profile text cannot be parsed. */
    val DEFAULT_TARGET: LocalTime = LocalTime.of(10, 0, 0)

    /** Parses `HH:mm:ss` (and `HH:mm`) strictly; returns `null` when the text is not a valid time. */
    fun parseTarget(raw: String?): LocalTime? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        for (pattern in TARGET_PATTERNS) {
            try {
                return LocalTime.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.US))
            } catch (error: DateTimeParseException) {
                // Try the next accepted spelling.
            }
        }
        return null
    }

    /** Parsed target, or ECP's default `10:00:00`. */
    fun resolvedTarget(raw: String?): LocalTime = parseTarget(raw) ?: DEFAULT_TARGET

    /** Percentage of the local day that has already elapsed, clamped to 0..100. */
    fun dayProgressPercent(time: LocalTime): Double {
        val seconds = time.toSecondOfDay() + time.nano / 1_000_000_000.0
        return (seconds / SECONDS_PER_DAY * 100.0).coerceIn(0.0, 100.0)
    }

    /** ECP target-mode derivation for the next daily occurrence of [targetRaw]. */
    fun targetMetrics(now: LocalDateTime, targetRaw: String?, chinese: Boolean = true): TargetMetrics {
        val target = resolvedTarget(targetRaw)
        var next = LocalDateTime.of(now.toLocalDate(), target)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val remainingMillis = Duration.between(now, next).toMillis().coerceAtLeast(0L)
        val remainingSeconds = ChronoUnit.SECONDS.between(now, next).coerceAtLeast(0L)
        val remainingPercent = (remainingMillis / MILLIS_PER_DAY * 100.0).coerceIn(0.0, 100.0)
        return TargetMetrics(
            target = target,
            nextOccurrence = next,
            remainingSeconds = remainingSeconds,
            remainingPercent = remainingPercent,
            progressPercent = 100.0 - remainingPercent,
            remainingText = remainingText(remainingPercent, chinese),
        )
    }

    /** `剩余{N}%` / `Left {N}%`, with .NET's away-from-zero rounding. */
    fun remainingText(percent: Double, chinese: Boolean): String {
        val rounded = round(percent).toInt()
        return if (chinese) "剩余$rounded%" else "Left $rounded%"
    }

    /** Normal (day-progress) status text: `"{N}%"`. */
    fun percentText(percent: Double): String = "${round(percent).toInt()}%"

    /** Localized weekday name for [day]. */
    fun weekdayText(day: DayOfWeek, chinese: Boolean): String =
        day.getDisplayName(TextStyle.FULL, if (chinese) Locale.SIMPLIFIED_CHINESE else Locale.US)

    fun formatClock(time: LocalTime): String = time.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US))

    fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
}
