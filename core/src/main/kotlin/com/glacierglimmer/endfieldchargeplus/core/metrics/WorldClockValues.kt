package com.glacierglimmer.endfieldchargeplus.core.metrics

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.WorldClockTimeZones
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor

/** Per-profile time from one real instant. A shared sampler cannot supply another HUD's zone. */
object WorldClockValues {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun effectiveSnapshot(
        profile: HudProfile,
        snapshot: MetricSnapshot,
        language: UiLanguage = UiLanguage.EN,
        instant: Instant = Instant.now(),
    ): MetricSnapshot = if (WorldClockTimeZones.usesUtcOffset(profile))
        snapshot.copy(values = snapshot.values + values(profile, instant, language)) else snapshot

    fun values(profile: HudProfile, instant: Instant, language: UiLanguage): Map<String, MetricValue> {
        val minutes = WorldClockTimeZones.normalize(profile.timeUtcOffsetMinutes ?: 0)
        val now = instant.atOffset(ZoneOffset.ofTotalSeconds(minutes * 60))
        val local = now.toLocalDateTime()
        val dayProgress = local.toLocalTime().toNanoOfDay() / 86_400_000_000_000.0 * 100
        val target = runCatching { LocalTime.parse(profile.timeTarget.trim()) }
            .getOrDefault(LocalTime.of(10, 0))
        var nextTarget = local.toLocalDate().atTime(target)
        if (nextTarget <= local) nextTarget = nextTarget.plusDays(1)
        val remainingSeconds = java.time.Duration.between(local, nextTarget).toNanos() / 1_000_000_000.0
        val remainingPercent = (remainingSeconds / 86400.0 * 100).coerceIn(0.0, 100.0)
        val roundedRemaining = floor(remainingPercent + 0.5).toInt()
        val remainingText = if (language.isEnglish) "Left $roundedRemaining%" else "剩余$roundedRemaining%"
        val status = if (profile.timeTargetEnabled) remainingText else "${floor(dayProgress + 0.5).toInt()}%"
        fun text(value: String) = MetricValue.Text(value)
        fun number(value: Double) = MetricValue.Number(value)
        return mapOf(
            Variables.TIME_CURRENT to text(clockFormat.format(now)),
            Variables.TIME_UTC_OFFSET to text(WorldClockTimeZones.offsetText(minutes)),
            Variables.TIME_DATE to text(dateFormat.format(now)),
            Variables.TIME_WEEKDAY to text(now.dayOfWeek.getDisplayName(TextStyle.FULL,
                if (language.isEnglish) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)),
            Variables.TIME_TIMESTAMP to number(instant.epochSecond.toDouble()),
            Variables.TIME_DAY_PROGRESS to number(dayProgress),
            Variables.TIME_TARGET to text(clockFormat.format(target)),
            Variables.TIME_TARGET_REMAINING_SECONDS to number(remainingSeconds),
            Variables.TIME_TARGET_REMAINING_TEXT to text(remainingText),
            Variables.TIME_TARGET_PROGRESS to number(100 - remainingPercent),
            Variables.TIME_DISPLAY_PROGRESS to number(if (profile.timeTargetEnabled) remainingPercent else dayProgress),
            Variables.TIME_DISPLAY_STATUS_TEXT to text(status),
        )
    }
}
