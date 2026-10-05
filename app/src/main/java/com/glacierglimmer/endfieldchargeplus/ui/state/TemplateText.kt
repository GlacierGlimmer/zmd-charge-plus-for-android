package com.glacierglimmer.endfieldchargeplus.ui.state

/**
 * Pure helpers for the `{variable|format}` template fields.
 *
 * The settings UI cannot fully validate a template (only the engine knows the live variables), but
 * it must catch the mistakes a user can see: unbalanced braces, an empty token, a token nested
 * inside an expression token, and an expression token with no body.
 */
object TemplateText {

    /** `{key}` or `{key|format}` — the same grammar the core tokenizer accepts. */
    val tokenRegex: Regex = Regex("""\{(?<key>[a-zA-Z0-9_.\-]+)(?:\|(?<format>[^}]*))?\}""")

    /** `{= expression}` including an optional `| format` tail. */
    val expressionRegex: Regex = Regex("""\{=(?<body>[^{}]*)\}""")

    /** A key that could appear in a template. */
    private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")

    /** Words that are operators or functions rather than variables. */
    private val reserved: Set<String> = setOf(
        "true", "false", "null", "and", "or", "not",
        "if", "min", "max", "avg", "sum", "clamp", "round", "floor", "ceil", "abs",
        "sqrt", "pow", "log", "log10", "exp", "sin", "cos", "tan", "sign",
        "len", "contains", "startswith", "endswith", "upper", "lower", "concat",
        "isnull", "isempty", "isnan", "number", "string", "bool", "percent", "between",
    )

    /** Which part of one token is wrong, if any. */
    enum class Issue { UNBALANCED_BRACES, EMPTY_KEY, NESTED_BRACES, EMPTY_EXPRESSION }

    /** Human readable problem for [Issue]; the UI maps it to a localized message. */
    data class Problem(val issue: Issue, val position: Int = -1)

    /** Returns the first problem of [text], or null when the template is syntactically usable. */
    fun validate(text: String?): Problem? {
        if (text.isNullOrEmpty()) return null
        var depth = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    if (depth > 0) return Problem(Issue.NESTED_BRACES, index)
                    depth++
                }

                '}' -> {
                    if (depth == 0) return Problem(Issue.UNBALANCED_BRACES, index)
                    depth--
                }
            }
            index++
        }
        if (depth != 0) return Problem(Issue.UNBALANCED_BRACES, text.length - 1)
        tokenRegex.findAll(text).forEach { match ->
            if (match.groups["key"]!!.value.isEmpty()) return Problem(Issue.EMPTY_KEY, match.range.first)
        }
        expressionRegex.findAll(text).forEach { match ->
            if (match.groups["body"]!!.value.isBlank()) {
                return Problem(Issue.EMPTY_EXPRESSION, match.range.first)
            }
        }
        return null
    }

    /** True when [text] is syntactically usable. */
    fun isValid(text: String?): Boolean = validate(text) == null

    /** Every variable key referenced by [text], including keys used inside expression tokens. */
    fun variableKeys(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val keys = LinkedHashSet<String>()
        tokenRegex.findAll(text).forEach { match ->
            val key = match.groups["key"]!!.value
            if (key.isNotEmpty()) keys += key
        }
        expressionRegex.findAll(text).forEach { match ->
            keys += expressionKeysOf(stripFormat(match.groups["body"]!!.value))
        }
        return keys.toList()
    }

    /**
     * Keys referenced by a progress value.
     *
     * Mirrors the desktop `TemplateEngine.ExtractExpressionKeys`: a bare key is itself, while
     * `= expression` and `{= expression | format }` contribute the identifiers of the expression.
     */
    fun progressKeys(source: String?): List<String> {
        if (source.isNullOrBlank()) return emptyList()
        val text = source.trim()
        return when {
            text.startsWith("{=") && text.endsWith("}") -> {
                val body = text.substring(2, text.length - 1)
                expressionKeysOf(stripFormat(body))
            }

            text.startsWith("=") -> expressionKeysOf(stripFormat(text.substring(1)))
            else -> listOf(text)
        }
    }

    /** Identifiers of an expression body, excluding function names and keywords. */
    private fun expressionKeysOf(body: String): List<String> {
        val keys = LinkedHashSet<String>()
        identifierRegex.findAll(body).forEach { identifier ->
            if (identifier.value !in reserved) keys += identifier.value
        }
        return keys.toList()
    }

    /**
     * Drops a `| format` tail at the first top-level pipe that is not part of `||`, exactly like the
     * desktop `SplitExpressionAndFormat`.
     */
    private fun stripFormat(body: String): String {
        var depth = 0
        var inString = false
        var quote = ' '
        body.forEachIndexed { index, char ->
            if (inString) {
                if (char == quote) inString = false
                return@forEachIndexed
            }
            when (char) {
                '\'', '"' -> {
                    inString = true
                    quote = char
                }

                '(' -> depth++
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                '|' -> {
                    val previous = body.getOrNull(index - 1)
                    val next = body.getOrNull(index + 1)
                    if (depth == 0 && previous != '|' && next != '|') return body.substring(0, index)
                }
            }
        }
        return body
    }

    /** Inserts `{key}` at [cursor], returning the new text and the new cursor position. */
    fun insertKey(text: String, key: String, cursor: Int): Pair<String, Int> {
        val safeCursor = cursor.coerceIn(0, text.length)
        val token = "{$key}"
        val updated = text.substring(0, safeCursor) + token + text.substring(safeCursor)
        return updated to (safeCursor + token.length)
    }

    /** The canonical template token for [key]. */
    fun token(key: String): String = "{$key}"

    /** True when the token carries an explicit format. */
    fun hasFormat(text: String?): Boolean =
        text != null && tokenRegex.findAll(text).any { it.groups["format"] != null }
}
