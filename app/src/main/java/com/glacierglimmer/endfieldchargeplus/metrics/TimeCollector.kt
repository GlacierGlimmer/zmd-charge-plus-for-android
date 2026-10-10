package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import java.time.Clock
import java.time.ZonedDateTime

/**
 * Clock, calendar and the profile's daily target mode.
 *
 * This is a pure local computation (no permission, no I/O) ported from the Windows edition, and it
 * goes through an injectable [Clock] so a fixed instant can be verified in tests. The current time
 * is always evaluated at collection time, never cached at construction.
 *
 * `time.display.progress` / `time.display.status_text` follow ECP exactly: target mode reports the
 * remaining fraction of the day ("剩余N%"), normal mode the elapsed day progress ("N%").
 */
class TimeCollector(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val environment: MetricEnvironment,
    private val clock: Clock = Clock.systemDefaultZone(),
) : MetricCollector {

    override val id: String = "time"

    override val tier: SamplingTier = SamplingTier.FAST

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val now = ZonedDateTime.now(clock)
        val local = now.toLocalDateTime()
        val chinese = !UiLanguage.fromAppLanguage(environment.config().language, LanguageController.systemLanguageTag()).isEnglish

        into[Variables.TIME_CURRENT] = MetricValue.Text(TimeMath.formatClock(local.toLocalTime()))
        into[Variables.TIME_DATE] = MetricValue.Text(TimeMath.formatDate(local.toLocalDate()))
        into[Variables.TIME_WEEKDAY] = MetricValue.Text(TimeMath.weekdayText(local.dayOfWeek, chinese))
        // Unix epoch seconds (the Android edition has no `time.unix_milliseconds` variable).
        into[Variables.TIME_TIMESTAMP] = MetricValue.Number(now.toEpochSecond().toDouble())

        val dayProgress = TimeMath.dayProgressPercent(local.toLocalTime())
        into[Variables.TIME_DAY_PROGRESS] = MetricValue.Number(dayProgress)

        val profile = environment.activeProfile()
        if (profile?.timeTargetEnabled == true) {
            val target = TimeMath.targetMetrics(local, profile.timeTarget, chinese)
            into[Variables.TIME_TARGET] = MetricValue.Text(TimeMath.formatClock(target.target))
            into[Variables.TIME_TARGET_REMAINING_SECONDS] = MetricValue.Number(target.remainingSeconds.toDouble())
            into[Variables.TIME_TARGET_REMAINING_TEXT] = MetricValue.Text(target.remainingText)
            into[Variables.TIME_TARGET_PROGRESS] = MetricValue.Number(target.progressPercent)
            into[Variables.TIME_DISPLAY_PROGRESS] = MetricValue.Number(target.remainingPercent)
            into[Variables.TIME_DISPLAY_STATUS_TEXT] = MetricValue.Text(target.remainingText)
        } else {
            into[Variables.TIME_DISPLAY_PROGRESS] = MetricValue.Number(dayProgress)
            into[Variables.TIME_DISPLAY_STATUS_TEXT] = MetricValue.Text(TimeMath.percentText(dayProgress))
            val detail = "active profile does not enable the daily target mode (TimeTargetEnabled=false)"
            into.putUnavailable(Variables.TIME_TARGET, UnavailableReason.DISABLED, detail)
            into.putUnavailable(Variables.TIME_TARGET_REMAINING_SECONDS, UnavailableReason.DISABLED, detail)
            into.putUnavailable(Variables.TIME_TARGET_REMAINING_TEXT, UnavailableReason.DISABLED, detail)
            into.putUnavailable(Variables.TIME_TARGET_PROGRESS, UnavailableReason.DISABLED, detail)
        }
    }
}
