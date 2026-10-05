package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.expression.ExpressionEngine
import com.glacierglimmer.endfieldchargeplus.core.expression.ExpressionResult
import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableAliases
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue

/**
 * The `{variable|format}` / `{= expression | format}` template renderer, ported from
 * `Customization/TemplateEngine.cs`.
 *
 * Grammar (identical to the desktop engine):
 *
 *  * `{key}` and `{key|format}` — key `[a-zA-Z0-9_.-]+`, format `[^}]+` (anything but `}`).
 *  * `{= expression}` and `{= expression | format}` — body `[^{}]*`, so a nested brace is not
 *    possible; the first top-level `|` that is not part of `||` splits expression and format.
 *  * Plain text between placeholders is copied verbatim. **There is no escape sequence**: the
 *    desktop engine uses plain `Regex.Replace`, so `{{cpu.usage}}` renders `{` + value + `}` while
 *    a brace that does not form a valid token is emitted literally.
 *  * Expression tokens are substituted **first**, then the result is scanned again for `{key}`
 *    tokens, so an expression that returns the literal text `{cpu.usage}` is substituted too.
 *
 * An unavailable value renders [UNKNOWN_SENTINEL] (the literal `--`), never a fabricated `0`:
 * a missing/`null` reading, a [MetricValue.Unavailable] reading and a failed expression all
 * produce the sentinel.
 */
object TemplateEngine {

    /**
     * Exactly what `TemplateEngine.cs:24,26,33` renders when a value is absent: the literal
     * two-character string `--`.
     *
     * The desktop editions use it for a missing key, a `null` value and a failed or `null`
     * `{=expression}` token; the Linux/macOS editions keep it as the final fallback after their
     * localized status strings. Android reproduces it byte for byte, so `{probe.loss_percent|0}`
     * with suffix `%` renders `--%` and never `0%`.
     */
    const val UNKNOWN_SENTINEL: String = "--"

    private val TOKEN_REGEX = Regex("""\{(?<key>[a-zA-Z0-9_.-]+)(?:\|(?<fmt>[^}]+))?\}""")
    private val EXPRESSION_REGEX = Regex("""\{=(?<body>[^{}]*)\}""")

    /**
     * Renders [template] against [resolver].
     *
     * @param template the template text; an empty template renders as the empty string.
     * @param resolver looks a canonical variable name up in the current snapshot; `null` and
     *   [MetricValue.Unavailable] both mean "no reading".
     * @param unavailableText replaced for every unavailable value, including failed expressions.
     */
    fun render(
        template: String,
        resolver: (String) -> MetricValue?,
        unavailableText: String = UNKNOWN_SENTINEL,
    ): String {
        if (template.isEmpty()) return ""

        // Advanced expression tokens are evaluated first; {variable|format} tokens stay fully
        // backward-compatible and are processed afterwards (desktop order).
        val withExpressions = EXPRESSION_REGEX.replace(template) { match ->
            val body = match.groups["body"]?.value.orEmpty()
            val (expression, format) = splitExpressionAndFormat(body)
            when (val result = ExpressionEngine.evaluate(expression, resolver)) {
                is ExpressionResult.Value -> ValueFormatter.formatNumberWithKey(result.number, format, expression)
                is ExpressionResult.Text -> ValueFormatter.formatText(result.text, format)
                is ExpressionResult.Failure -> unavailableText
            }
        }

        return TOKEN_REGEX.replace(withExpressions) { match ->
            val key = match.groups["key"]?.value.orEmpty()
            val canonical = VariableAliases.canonical(key)
            val format = match.groups["fmt"]?.value ?: ""
            val value = resolver(canonical)
            if (value == null || value is MetricValue.Unavailable) {
                unavailableText
            } else {
                ValueFormatter.formatWithKey(value, format, canonical)
            }
        }
    }

    /**
     * Every variable referenced by [templates], with legacy aliases already canonicalised.
     *
     * Both `{key}` and `{= expression}` tokens contribute, mirroring
     * `TemplateEngine.ExtractKeys`; this is what the capability filter and the collector request
     * planner use.
     */
    fun extractKeys(vararg templates: String?): Set<String> {
        val result = LinkedHashSet<String>()
        for (template in templates) {
            if (template.isNullOrEmpty()) continue
            for (match in TOKEN_REGEX.findAll(template)) {
                match.groups["key"]?.value?.let { result.add(VariableAliases.canonical(it)) }
            }
            for (match in EXPRESSION_REGEX.findAll(template)) {
                val body = match.groups["body"]?.value.orEmpty()
                val (expression, _) = splitExpressionAndFormat(body)
                for (key in ExpressionEngine.extractVariables(expression)) {
                    result.add(VariableAliases.canonical(key))
                }
            }
        }
        return result
    }

    /**
     * The numeric bridge for `ProgressVariable`, mirroring `TemplateEngine.EvaluateNumber`.
     *
     * Accepts a bare key, `= expression` or `{= expression [| format] }`; any failure returns
     * [fallback] (the desktop call site passes `ProgressMin`).
     */
    fun evaluateNumber(
        source: String?,
        resolver: (String) -> MetricValue?,
        fallback: Double = 0.0,
    ): Double {
        if (source.isNullOrBlank()) return fallback
        var text = source.trim()
        if (text.startsWith("{=") && text.endsWith("}")) {
            text = text.substring(2, text.length - 1)
        } else if (text.startsWith("=")) {
            text = text.substring(1)
        } else {
            val value = resolver(VariableAliases.canonical(text))
            return when (value) {
                is MetricValue.Number -> value.value
                is MetricValue.Text -> value.value.trim().toDoubleOrNull() ?: fallback
                else -> fallback
            }
        }
        val (expression, _) = splitExpressionAndFormat(text)
        return when (val result = ExpressionEngine.evaluate(expression, resolver)) {
            is ExpressionResult.Value -> result.number
            is ExpressionResult.Text -> result.text.trim().toDoubleOrNull() ?: fallback
            is ExpressionResult.Failure -> fallback
        }
    }

    /**
     * The variables referenced by a progress value.
     *
     * A bare key returns itself, `= expression` and `{= expression [| format] }` return the
     * expression's variables, exactly like `TemplateEngine.ExtractExpressionKeys`. Legacy aliases
     * are canonicalised.
     */
    fun extractExpressionKeys(source: String?): Set<String> {
        if (source.isNullOrBlank()) return emptySet()
        var text = source.trim()
        if (text.startsWith("{=") && text.endsWith("}")) {
            text = text.substring(2, text.length - 1)
        } else if (text.startsWith("=")) {
            text = text.substring(1)
        } else {
            return setOf(VariableAliases.canonical(text))
        }
        val (expression, _) = splitExpressionAndFormat(text)
        val keys = LinkedHashSet<String>()
        for (key in ExpressionEngine.extractVariables(expression)) keys.add(VariableAliases.canonical(key))
        return keys
    }

    /**
     * Splits `expression | format` at the first top-level `|` that is not part of `||`, honouring
     * quoted strings and parentheses, exactly like `TemplateEngine.SplitExpressionAndFormat`.
     */
    fun splitExpressionAndFormat(body: String): Pair<String, String> {
        var inString = false
        var quote = '\u0000'
        var depth = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == quote) inString = false
                i++
                continue
            }
            when (c) {
                '\'', '"' -> {
                    inString = true
                    quote = c
                }
                '(' -> depth++
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                '|' -> {
                    val isLogical = (i > 0 && body[i - 1] == '|') || (i + 1 < body.length && body[i + 1] == '|')
                    if (!isLogical && depth == 0) {
                        return body.substring(0, i).trim() to body.substring(i + 1).trim()
                    }
                }
            }
            i++
        }
        return body.trim() to ""
    }
}
