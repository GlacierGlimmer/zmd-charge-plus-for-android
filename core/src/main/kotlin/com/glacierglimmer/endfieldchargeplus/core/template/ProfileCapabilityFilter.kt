package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableAliases
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import java.util.Locale

/**
 * Pure capability filter for HUD schemes, ported from the Linux/macOS
 * `RemoveUnsupportedReferences` rule (`LinuxVariableCatalog.cs:167-183`).
 *
 * ECP's product rule is "strip unsupported references instead of rendering blanks" while the
 * original configuration file is preserved by the caller (`settings.previous.json`, import
 * backups). Android keeps the same contract:
 *
 *  * every `{...}` token whose variable is unsupported is removed from all seven template fields;
 *  * a `ProgressVariable` that references an unsupported variable becomes the empty string;
 *  * colour rules whose variable is unsupported are dropped.
 *
 * For `{= expression | format}` the whole token is removed as soon as **any** variable the
 * expression references is unsupported: an expression cannot be evaluated for a missing operand,
 * and rewriting it would corrupt it. Plain literals around a removed token are left untouched.
 *
 * The filter is pure and case-insensitive; a profile that needs no change is returned unchanged
 * (the same instance), so callers can compare identity before writing anything back.
 */
object ProfileCapabilityFilter {

    /** Matches any brace group without nested braces, exactly like the desktop `Regex("[^{}]+")`. */
    private val TOKEN_REGEX = Regex("""\{[^{}]+\}""")

    /**
     * Removes every template reference, progress reference and colour rule that depends on one of
     * [unsupportedVariables].
     *
     * @param unsupportedVariables keys the current device cannot provide (see
     *   `VariableRegistry`/the capability catalog). Legacy aliases are canonicalised, so passing
     *   either `ping.loss_percent` or `probe.loss_percent` filters both spellings.
     */
    fun filter(profile: HudProfile, unsupportedVariables: Set<String>): HudProfile {
        if (unsupportedVariables.isEmpty()) return profile
        val unsupported = HashSet<String>(unsupportedVariables.size * 2)
        for (variable in unsupportedVariables) {
            unsupported.add(variable.trim().lowercase(Locale.ROOT))
            unsupported.add(VariableAliases.canonical(variable.trim()).lowercase(Locale.ROOT))
        }
        fun available(key: String): Boolean {
            val canonical = VariableAliases.canonical(key.trim()).lowercase(Locale.ROOT)
            return canonical !in unsupported
        }

        fun clean(template: String): String = TOKEN_REGEX.replace(template) { match ->
            val keys = TemplateEngine.extractKeys(match.value)
            if (keys.any { !available(it) }) "" else match.value
        }

        val tagline = clean(profile.taglineTemplate)
        val title = clean(profile.titleTemplate)
        val primary = clean(profile.primaryTemplate)
        val secondary = clean(profile.secondaryTemplate)
        val right = clean(profile.rightTemplate)
        val suffix = clean(profile.rightSuffix)
        val progress = if (TemplateEngine.extractExpressionKeys(profile.progressVariable).any { !available(it) }) {
            ""
        } else {
            profile.progressVariable
        }
        val colorRules = profile.colorRules.filter { rule ->
            rule.variable.isNotBlank() && available(rule.variable)
        }
        val unchanged = tagline == profile.taglineTemplate &&
            title == profile.titleTemplate &&
            primary == profile.primaryTemplate &&
            secondary == profile.secondaryTemplate &&
            right == profile.rightTemplate &&
            suffix == profile.rightSuffix &&
            progress == profile.progressVariable &&
            colorRules.size == profile.colorRules.size
        if (unchanged) return profile
        return profile.copy(
            taglineTemplate = tagline,
            titleTemplate = title,
            primaryTemplate = primary,
            secondaryTemplate = secondary,
            rightTemplate = right,
            rightSuffix = suffix,
            progressVariable = progress,
            colorRules = colorRules,
        )
    }
}
