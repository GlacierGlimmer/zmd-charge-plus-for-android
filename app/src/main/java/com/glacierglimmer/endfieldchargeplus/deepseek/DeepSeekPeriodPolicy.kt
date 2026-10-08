package com.glacierglimmer.endfieldchargeplus.deepseek

import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToLong

/** Official billing rule: Mon–Fri 09–12 and 14–18, excluding Chinese holidays; ALL weekends idle. */
object DeepSeekPeriodPolicy {
    const val OFFICIAL_WINDOWS = "09:00-12:00;14:00-18:00"
    private val windows = listOf(LocalTime.of(9, 0) to LocalTime.of(12, 0), LocalTime.of(14, 0) to LocalTime.of(18, 0))

    data class State(val beijing: ZonedDateTime, val isPeak: Boolean?, val segmentStart: ZonedDateTime?, val nextTransition: ZonedDateTime?) {
        val nameZh: String get() = when (isPeak) { true -> "高峰"; false -> "低谷"; null -> "日历待更新" }
        val nameEn: String get() = when (isPeak) { true -> "PEAK"; false -> "OFF-PEAK"; null -> "UNKNOWN" }
        val remainingSeconds: Double? get() = nextTransition?.let { Duration.between(beijing, it).toMillis().coerceAtLeast(0) / 1000.0 }
        val progressPercent: Double? get() {
            val start = segmentStart ?: return null
            val end = nextTransition ?: return null
            val total = Duration.between(start, end).toMillis()
            return if (total > 0) (Duration.between(start, beijing).toMillis() / total.toDouble() * 100).coerceIn(0.0, 100.0) else null
        }
        fun remainingText(english: Boolean): String {
            val seconds = remainingSeconds ?: return unknownText(english)
            val value = seconds.toLong()
            val duration = String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value % 3600 / 60, value % 60)
            return if (english) "${if (isPeak == true) "Peak" else "Off-peak"} left $duration" else "${nameZh}时段剩余$duration"
        }
        fun progressText(english: Boolean): String {
            val progress = progressPercent?.roundToLong() ?: return unknownText(english)
            return if (english) "${if (isPeak == true) "Peak" else "Off-peak"} $progress%" else "${nameZh}已过$progress%"
        }
        private fun unknownText(english: Boolean) = if (english) "Holiday calendar needs an update" else "节假日日历待更新"
    }

    fun evaluate(instant: ZonedDateTime): State {
        val now = instant.withZoneSameInstant(PeakWindow.BEIJING)
        val eligible = ChinaWorkCalendar.isPeakDay(now.toLocalDate())
        val window = windows.firstOrNull { (start, end) -> !now.toLocalTime().isBefore(start) && now.toLocalTime().isBefore(end) }
        if (eligible == null && window != null) return State(now, null, null, null)
        val active = if (eligible == true) window else null
        val start = active?.let { now.toLocalDate().atTime(it.first).atZone(PeakWindow.BEIJING) } ?: findBoundary(now, false)
        return State(now, active != null, start, findBoundary(now, true))
    }

    private fun findBoundary(now: ZonedDateTime, forward: Boolean): ZonedDateTime? {
        for (offset in 0L..369L) {
            val date = now.toLocalDate().plusDays(if (forward) offset else -offset)
            val eligible = ChinaWorkCalendar.isPeakDay(date) ?: return null
            if (!eligible) continue
            val boundaries = windows.flatMap { (start, end) -> listOf(date.atTime(start).atZone(PeakWindow.BEIJING), date.atTime(end).atZone(PeakWindow.BEIJING)) }
            for (boundary in if (forward) boundaries else boundaries.asReversed()) {
                if (if (forward) boundary.isAfter(now) else !boundary.isAfter(now)) return boundary
            }
        }
        return null
    }
}
