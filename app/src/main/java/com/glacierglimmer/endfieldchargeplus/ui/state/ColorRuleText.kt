package com.glacierglimmer.endfieldchargeplus.ui.state

import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import kotlin.math.abs

/**
 * The "variable operator value => colour" rule text used by the colour-rule editor.
 *
 * This is a faithful port of `HudCustomizerView.ParseColorRules` from the Windows edition
 * (`Customization/HudCustomizerView.axaml.cs:1143-1161`), including its regex and its silent
 * skipping of malformed lines, so a configuration copy-pasted between platforms behaves the same.
 */
object ColorRuleText {

    /** `variable operator value => #RRGGBB[AA]`, whitespace tolerant. */
    val ruleRegex: Regex = Regex(
        buildString {
            append("""^\s*(?<variable>[A-Za-z0-9_.\-]+)\s*""")
            append("""(?<operator>>=|<=|==|!=|>|<)\s*""")
            append("""(?<value>-?[0-9]+(?:\.[0-9]+)?)\s*=>\s*""")
            append("""(?<color>#[0-9A-Fa-f]{6,8})\s*""")
            append('$')
        },
    )

    /** Operators accepted by both the editor and the renderer. */
    val operators: List<String> = listOf(">=", "<=", ">", "<", "==", "!=")

    /** Parses every valid line; malformed lines are skipped exactly like the desktop editor. */
    fun parse(text: String?): List<HudColorRule> {
        if (text.isNullOrBlank()) return emptyList()
        val rules = mutableListOf<HudColorRule>()
        text.lineSequence().forEach { line ->
            val match = ruleRegex.matchEntire(line) ?: return@forEach
            val value = match.groups["value"]?.value?.toDoubleOrNull() ?: return@forEach
            rules += HudColorRule(
                variable = match.groups["variable"]!!.value,
                operator = match.groups["operator"]!!.value,
                value = value,
                color = match.groups["color"]!!.value,
            )
        }
        return rules
    }

    /** 1-based line numbers of non-blank lines that could not be parsed, for inline validation. */
    fun invalidLines(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .mapIndexedNotNull { index, line ->
                if (line.isBlank() || ruleRegex.matchEntire(line) != null) null else index + 1
            }
            .toList()
    }

    /** Serializes one rule the same way the desktop editor does. */
    fun format(rule: HudColorRule): String =
        "${rule.variable} ${rule.operator} ${numberText(rule.value)} => ${rule.color}"

    /** Serializes the whole rule list, one rule per line. */
    fun format(rules: List<HudColorRule>): String =
        rules.joinToString(separator = "\n") { format(it) }

    /**
     * Renders a double the way .NET's `ToString(InvariantCulture)` does for the values this editor
     * produces: no trailing `.0` and no scientific notation for ordinary thresholds.
     */
    fun numberText(value: Double): String {
        if (!value.isFinite()) return "0"
        if (abs(value - value.toLong().toDouble()) < 1e-9) return value.toLong().toString()
        return value.toString().trimEnd('0').trimEnd('.')
    }
}
