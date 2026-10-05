package com.glacierglimmer.endfieldchargeplus.hud

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData

/**
 * Turns one profile plus one metric snapshot into the strings and colours the renderers draw.
 *
 * The overlay view, the settings preview and the island providers all call this same builder, so a
 * scheme always looks the same regardless of the output backend.
 */
interface HudStateBuilder {

    fun build(
        profile: HudProfile,
        snapshot: MetricSnapshot,
        language: UiLanguage,
    ): HudRenderData

    /**
     * Evaluates the profile's colour rules against the snapshot and returns the accent colour that
     * should be used, or the profile accent when no rule matches. Rules are evaluated in order and
     * the first match wins, exactly like the desktop editions.
     */
    fun resolveAccent(profile: HudProfile, snapshot: MetricSnapshot): String

    /** Progress ring value clamped into the profile's configured minimum/maximum. */
    fun resolveProgress(profile: HudProfile, snapshot: MetricSnapshot): Double

    /** The rule that matched, for the settings UI preview and diagnostics. */
    fun matchingRule(profile: HudProfile, snapshot: MetricSnapshot): HudColorRule?
}
