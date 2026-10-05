package com.glacierglimmer.endfieldchargeplus.hud

import com.glacierglimmer.endfieldchargeplus.core.i18n.BuiltInProfileLocalization
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableAliases
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine
import kotlin.math.abs

/**
 * Dependency-free [HudStateBuilder]: the Android port of `HudProfileRenderer.Render`.
 *
 * One profile plus one [MetricSnapshot] produce the exact strings, ring value and accent colour the
 * overlay, the settings preview and the island providers draw, so a scheme looks identical on every
 * output backend. The class touches no Android API and is therefore unit-testable on the JVM.
 *
 * Behaviour notes (all mirroring the desktop renderer):
 *
 *  * Built-in schemes are localized first ([BuiltInProfileLocalization.forLanguage]), so the
 *    English overlay changes the title, the right template and the display name only.
 *  * Every template field is rendered through [TemplateEngine]; an unavailable reading renders the
 *    `--` sentinel, never `0`.
 *  * The snapshot is expected to already contain the profile-derived values
 *    (`time.display.*`, `time.target.*`, `network.display_*`, `network.profile_*`,
 *    `deepseek.period.*`); the collector side owns that derivation, exactly like
 *    `HudProfileRenderer.BuildEffectiveVariables` does on the desktop.
 *  * [resolveProgress] returns the **0..1 ring fraction** (`(raw - min) / (max - min)`, clamped),
 *    the same value stored in [HudRenderData.progress].
 *  * Colour rules are evaluated in order, the first match wins; a rule whose variable is missing,
 *    unavailable or textual never matches (a status string is not coerced to a number).
 */
class HudStateBuilderImpl : HudStateBuilder {

    /** Renders one frame of HUD text, ring progress and accent colour. */
    override fun build(
        profile: HudProfile,
        snapshot: MetricSnapshot,
        language: UiLanguage,
    ): HudRenderData {
        val localized = BuiltInProfileLocalization.forLanguage(profile, language)
        val resolver = resolverFor(snapshot)
        return HudRenderData(
            tagline = TemplateEngine.render(localized.taglineTemplate, resolver),
            title = TemplateEngine.render(localized.titleTemplate, resolver),
            primaryText = TemplateEngine.render(localized.primaryTemplate, resolver),
            secondaryText = TemplateEngine.render(localized.secondaryTemplate, resolver),
            rightText = TemplateEngine.render(localized.rightTemplate, resolver),
            rightSuffix = TemplateEngine.render(localized.rightSuffix, resolver),
            progress = resolveProgress(localized, snapshot),
            leftIcon = localized.leftIcon,
            rightIcon = localized.rightIcon,
            accentColor = resolveAccent(localized, snapshot),
            simpleAnimation = !localized.animationMode.equals("Full", ignoreCase = true),
        )
    }

    /** The 0..1 progress-ring fraction, clamped into the profile's configured span. */
    override fun resolveProgress(profile: HudProfile, snapshot: MetricSnapshot): Double {
        val raw = TemplateEngine.evaluateNumber(
            profile.progressVariable,
            resolverFor(snapshot),
            profile.progressMin,
        )
        val span = profile.progressMax - profile.progressMin
        if (span <= 0.0) return 0.0
        return ((raw - profile.progressMin) / span).coerceIn(0.0, 1.0)
    }

    /** The colour of the first matching rule, or the profile accent when no rule matches. */
    override fun resolveAccent(profile: HudProfile, snapshot: MetricSnapshot): String =
        matchingRule(profile, snapshot)?.color ?: profile.accentColor

    /** The first rule whose numeric comparison succeeds, or `null`. */
    override fun matchingRule(profile: HudProfile, snapshot: MetricSnapshot): HudColorRule? {
        for (rule in profile.colorRules) {
            if (rule.variable.isBlank()) continue
            val reading = snapshot[VariableAliases.canonical(rule.variable)] as? MetricValue.Number ?: continue
            val actual = reading.value
            val matches = when (rule.operator.trim()) {
                ">" -> actual > rule.value
                ">=" -> actual >= rule.value
                "<" -> actual < rule.value
                "<=" -> actual <= rule.value
                "==" -> abs(actual - rule.value) < 0.000001
                "!=" -> abs(actual - rule.value) >= 0.000001
                else -> false
            }
            if (matches) return rule
        }
        return null
    }

    private fun resolverFor(snapshot: MetricSnapshot): (String) -> MetricValue? =
        { name -> snapshot[VariableAliases.canonical(name)] }
}
