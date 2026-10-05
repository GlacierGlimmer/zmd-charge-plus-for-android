package com.glacierglimmer.endfieldchargeplus.core.expression

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.metrics.VariableAliases
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.template.DotNetNumber
import java.util.Locale

/**
 * Result of evaluating one `{= expression }` token.
 *
 * [Failure] covers every desktop failure mode: a parse error, an arity error, a non-numeric
 * operand, a division by zero, and a value that is unavailable. The template layer renders the
 * sentinel for it, which is exactly what `TemplateEngine.cs` does with `TryEvaluate == false` or a
 * `null` result.
 */
sealed interface ExpressionResult {

    /** A numeric result. */
    data class Value(val number: Double) : ExpressionResult

    /** A textual result (strings, and the `true`/`false` rendering of a boolean). */
    data class Text(val text: String) : ExpressionResult

    /** The expression could not produce a value; [message] is the localized diagnostic. */
    data class Failure(val message: String) : ExpressionResult
}

/**
 * Sandboxed expression evaluator, ported from `Customization/ExpressionEngine.cs`.
 *
 * Grammar and precedence (lowest to highest) are identical to the desktop engine:
 *
 * ```
 * Parse          := Ternary EOF
 * Ternary        := Coalesce [ '?' Ternary ':' Ternary ]     // right associative, eager
 * Coalesce       := Or ( '??' Or )*                          // also replaces an empty string
 * Or             := And ( ('||' | 'or') And )*
 * And            := Equality ( ('&&' | 'and') Equality )*
 * Equality       := Comparison ( ('==' | '!=') Comparison )*
 * Comparison     := Additive ( ('>=' | '<=' | '>' | '<') Additive )*
 * Additive       := Multiplicative ( ('+' | '-') Multiplicative )*
 * Multiplicative := Power ( ('*' | '/' | '%') Power )*
 * Power          := Unary [ '^' Power ]                      // right associative, Math.Pow
 * Unary          := ('!' | 'not' | '+' | '-') Unary | Primary
 * Primary        := '(' Ternary ')' | string | number | 'true' | 'false' | 'null'
 *                 | identifier [ '(' args ')' ]
 * ```
 *
 * The full 29-function whitelist of the desktop engine is implemented: `if`, `min`, `max`, `avg`,
 * `sum`, `clamp`, `round`, `floor`, `ceil`, `abs`, `sqrt`, `pow`, `log`, `log10`, `exp`, `sin`,
 * `cos`, `tan`, `sign`, `len`, `contains`, `startswith`, `endswith`, `upper`, `lower`, `concat`,
 * `isnull`, `isempty`, `isnan`, `number`, `string`, `bool`, `percent`, `between`.
 *
 * **Unavailable values.** A variable the resolver reports as missing or
 * [MetricValue.Unavailable] evaluates to the internal *unavailable* value, which is the port of
 * the desktop `null`. It behaves like `null` for truthiness (`false`), `isnull` (`true`),
 * `isempty` (`true`), `string` (`""`) and `??`; but it **propagates** instead of being coerced to
 * `0` by arithmetic, numeric functions and comparisons. That is the Android reading of the ECP
 * rule "never fabricate a value": `{= cpu.usage}` and `{= cpu.usage + 1}` both render the `--`
 * sentinel when `cpu.usage` is unavailable, while `{= cpu.usage ?? 0}` still yields `0` and
 * `{= if(isnull(cpu.usage), 'N/A', cpu.usage)}` still yields `N/A`.
 *
 * Division and modulo by zero are surfaced as [ExpressionResult.Failure] (the desktop throws
 * `DivideByZeroException`); `clamp(v, lo, hi)` with `lo > hi` is likewise a failure rather than the
 * uncaught `ArgumentException` the desktop engine lets escape.
 */
object ExpressionEngine {

    /** Case-insensitive keywords that are never variable references. */
    private val KEYWORDS = setOf("true", "false", "null", "and", "or", "not")

    /** The whitelisted function names, used by [extractVariables] and [call]. */
    private val FUNCTION_NAMES = setOf(
        "if", "min", "max", "avg", "sum", "clamp", "round", "floor", "ceil", "abs",
        "sqrt", "pow", "log", "log10", "exp", "sin", "cos", "tan", "sign",
        "len", "contains", "startswith", "endswith", "upper", "lower", "concat",
        "isnull", "isempty", "isnan", "number", "string", "bool", "percent", "between",
    )

    /**
     * Evaluates [expression].
     *
     * @param resolver looks up a variable; the name is canonicalised through
     *   [VariableAliases.canonical] first, so legacy `ping.*` spellings keep working.
     */
    fun evaluate(expression: String, resolver: (String) -> MetricValue?): ExpressionResult {
        return try {
            when (val node = Parser(expression, resolver).parse()) {
                is Node.Num -> ExpressionResult.Value(node.value)
                is Node.Str -> ExpressionResult.Text(node.value)
                is Node.Bool -> ExpressionResult.Text(if (node.value) "true" else "false")
                Node.Unavailable -> ExpressionResult.Failure(Strings.t("值不可用", "Value is unavailable"))
            }
        } catch (failure: ExpressionFailure) {
            ExpressionResult.Failure(failure.message ?: Strings.t("表达式错误", "Expression error"))
        }
    }

    /**
     * The variable names referenced by [expression], mirroring
     * `ExpressionEngine.ExtractVariables`: quoted strings, keywords and function names are skipped.
     */
    fun extractVariables(expression: String): Set<String> {
        val result = LinkedHashSet<String>()
        if (expression.isBlank()) return result
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            if (c == '\'' || c == '"') {
                val quote = c
                i++
                while (i < expression.length) {
                    if (expression[i] == '\\') {
                        i += minOf(2, expression.length - i)
                        continue
                    }
                    val current = expression[i]
                    i++
                    if (current == quote) break
                }
                continue
            }
            if (isIdentifierStart(c)) {
                val start = i
                i++
                while (i < expression.length && isIdentifierPart(expression[i])) i++
                val identifier = expression.substring(start, i)
                var j = i
                while (j < expression.length && expression[j].isWhitespace()) j++
                val isFunctionCall = j < expression.length && expression[j] == '('
                val lower = identifier.lowercase(Locale.ROOT)
                if (!isFunctionCall && lower !in KEYWORDS && lower !in FUNCTION_NAMES) result.add(identifier)
                continue
            }
            i++
        }
        return result
    }

    /** The internal value model: the port of the desktop engine's `object?` domain. */
    private sealed interface Node {
        data class Num(val value: Double) : Node
        data class Str(val value: String) : Node
        data class Bool(val value: Boolean) : Node
        data object Unavailable : Node
    }

    /** A numeric operand or the unavailable marker, used by the coercion helpers. */
    private sealed interface NumOr {
        data class Num(val value: Double) : NumOr
        data object Unavailable : NumOr
    }

    /** Raised for every desktop `FormatException`/`DivideByZeroException`/parse error. */
    private class ExpressionFailure(message: String) : RuntimeException(message)

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    /** The recursive-descent parser; one instance per [evaluate] call. */
    private class Parser(
        private val text: String,
        private val resolver: (String) -> MetricValue?,
    ) {
        private var position = 0

        fun parse(): Node {
            val value = parseTernary()
            skipWhite()
            if (position != text.length) {
                throw error(Strings.t("无法识别的内容：${text.substring(position)}", "Unrecognized content: ${text.substring(position)}"))
            }
            return value
        }

        private fun parseTernary(): Node {
            val condition = parseCoalesce()
            skipWhite()
            if (!match("?")) return condition
            val whenTrue = parseTernary()
            require(":")
            val whenFalse = parseTernary()
            return if (toBool(condition)) whenTrue else whenFalse
        }

        private fun parseCoalesce(): Node {
            var left = parseOr()
            while (true) {
                skipWhite()
                if (!match("??")) return left
                val right = parseOr()
                if (left is Node.Unavailable || (left is Node.Str && left.value.isEmpty())) left = right
            }
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (true) {
                skipWhite()
                if (match("||") || matchWord("or")) {
                    val right = parseAnd()
                    left = Node.Bool(toBool(left) || toBool(right))
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): Node {
            var left = parseEquality()
            while (true) {
                skipWhite()
                if (match("&&") || matchWord("and")) {
                    val right = parseEquality()
                    left = Node.Bool(toBool(left) && toBool(right))
                } else {
                    return left
                }
            }
        }

        private fun parseEquality(): Node {
            var left = parseComparison()
            while (true) {
                skipWhite()
                left = when {
                    match("==") -> Node.Bool(valuesEqual(left, parseComparison()))
                    match("!=") -> Node.Bool(!valuesEqual(left, parseComparison()))
                    else -> return left
                }
            }
        }

        private fun parseComparison(): Node {
            var left = parseAdditive()
            while (true) {
                skipWhite()
                left = when {
                    match(">=") -> compare(left, parseAdditive()) { it >= 0 }
                    match("<=") -> compare(left, parseAdditive()) { it <= 0 }
                    match(">") -> compare(left, parseAdditive()) { it > 0 }
                    match("<") -> compare(left, parseAdditive()) { it < 0 }
                    else -> return left
                }
            }
        }

        private fun parseAdditive(): Node {
            var left = parseMultiplicative()
            while (true) {
                skipWhite()
                when {
                    match("+") -> {
                        val right = parseMultiplicative()
                        left = when {
                            left is Node.Str || right is Node.Str ->
                                if (left is Node.Unavailable || right is Node.Unavailable) {
                                    Node.Unavailable
                                } else {
                                    Node.Str(toText(left) + toText(right))
                                }
                            else -> combine(toNumeric(left), toNumeric(right)) { a, b -> a + b }
                        }
                    }
                    match("-") -> {
                        val leftNumber = toNumeric(left)
                        val rightNumber = toNumeric(parseMultiplicative())
                        left = combine(leftNumber, rightNumber) { a, b -> a - b }
                    }
                    else -> return left
                }
            }
        }

        private fun parseMultiplicative(): Node {
            var left = parsePower()
            while (true) {
                skipWhite()
                when {
                    match("*") -> {
                        val leftNumber = toNumeric(left)
                        val rightNumber = toNumeric(parsePower())
                        left = combine(leftNumber, rightNumber) { a, b -> a * b }
                    }
                    match("/") -> {
                        val divisor = toNumeric(parsePower())
                        if (divisor is NumOr.Num && kotlin.math.abs(divisor.value) < Double.MIN_VALUE) {
                            throw ExpressionFailure(Strings.t("表达式除数不能为 0", "Expression divisor cannot be 0"))
                        }
                        val leftNumber = toNumeric(left)
                        left = combine(leftNumber, divisor) { a, b -> a / b }
                    }
                    match("%") -> {
                        val divisor = toNumeric(parsePower())
                        if (divisor is NumOr.Num && kotlin.math.abs(divisor.value) < Double.MIN_VALUE) {
                            throw ExpressionFailure(Strings.t("表达式取模除数不能为 0", "Modulo divisor cannot be 0"))
                        }
                        val leftNumber = toNumeric(left)
                        left = combine(leftNumber, divisor) { a, b -> a % b }
                    }
                    else -> return left
                }
            }
        }

        private fun parsePower(): Node {
            val left = parseUnary()
            skipWhite()
            if (match("^")) {
                val leftNumber = toNumeric(left)
                val rightNumber = toNumeric(parsePower())
                return combine(leftNumber, rightNumber) { a, b -> Math.pow(a, b) }
            }
            return left
        }

        private fun parseUnary(): Node {
            skipWhite()
            if (match("!")) return Node.Bool(!toBool(parseUnary()))
            if (matchWord("not")) return Node.Bool(!toBool(parseUnary()))
            if (match("+")) return numericNode(toNumeric(parseUnary()))
            if (match("-")) {
                val operand = toNumeric(parseUnary())
                return if (operand is NumOr.Num) Node.Num(-operand.value) else Node.Unavailable
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Node {
            skipWhite()
            if (position >= text.length) {
                throw error(Strings.t("表达式意外结束", "Unexpected end of expression"))
            }
            if (match("(")) {
                val value = parseTernary()
                require(")")
                return value
            }
            val c = text[position]
            if (c == '\'' || c == '"') return Node.Str(parseString())
            if (c.isDigit() || (c == '.' && position + 1 < text.length && text[position + 1].isDigit())) {
                return Node.Num(parseNumber())
            }
            if (isIdentifierStart(c)) {
                val identifier = parseIdentifier()
                if (identifier.equals("true", ignoreCase = true)) return Node.Bool(true)
                if (identifier.equals("false", ignoreCase = true)) return Node.Bool(false)
                if (identifier.equals("null", ignoreCase = true)) return Node.Unavailable
                skipWhite()
                if (match("(")) {
                    val args = ArrayList<Node>()
                    skipWhite()
                    if (!match(")")) {
                        do {
                            args.add(parseTernary())
                            skipWhite()
                        } while (match(","))
                        require(")")
                    }
                    return call(identifier, args)
                }
                return fromMetric(resolver(VariableAliases.canonical(identifier)))
            }
            throw error(Strings.t("无法识别字符 '$c'", "Unrecognized character '$c'"))
        }

        private fun call(name: String, args: List<Node>): Node {
            val function = name.lowercase(Locale.ROOT)
            return when (function) {
                "if" -> {
                    requireCount(name, args, 3)
                    if (toBool(args[0])) args[1] else args[2]
                }
                "min" -> reduceNumbers(name, args, 1) { values -> Node.Num(values.min()) }
                "max" -> reduceNumbers(name, args, 1) { values -> Node.Num(values.max()) }
                "avg" -> reduceNumbers(name, args, 1) { values -> Node.Num(values.average()) }
                "sum" -> sumNumbers(args)
                "clamp" -> clamp(name, args)
                "round" -> round(name, args)
                "floor" -> unaryNumber(name, args) { Node.Num(kotlin.math.floor(it)) }
                "ceil" -> unaryNumber(name, args) { Node.Num(kotlin.math.ceil(it)) }
                "abs" -> unaryNumber(name, args) { Node.Num(kotlin.math.abs(it)) }
                "sqrt" -> unaryNumber(name, args) { Node.Num(Math.sqrt(kotlin.math.max(0.0, it))) }
                "pow" -> binaryNumber(name, args) { a, b -> Node.Num(Math.pow(a, b)) }
                "log" -> unaryNumber(name, args) { Node.Num(Math.log(it)) }
                "log10" -> unaryNumber(name, args) { Node.Num(Math.log10(it)) }
                "exp" -> unaryNumber(name, args) { Node.Num(Math.exp(it)) }
                "sin" -> unaryNumber(name, args) { Node.Num(Math.sin(it)) }
                "cos" -> unaryNumber(name, args) { Node.Num(Math.cos(it)) }
                "tan" -> unaryNumber(name, args) { Node.Num(Math.tan(it)) }
                "sign" -> unaryNumber(name, args) {
                    if (it.isNaN()) throw ExpressionFailure(
                        Strings.t("函数 sign 不接受 NaN", "Function sign does not accept NaN"),
                    )
                    Node.Num(Math.signum(it))
                }
                "len" -> {
                    requireCount(name, args, 1)
                    Node.Num(toText(args[0]).length.toDouble())
                }
                "contains" -> binaryText(name, args) { a, b -> a.contains(b, ignoreCase = true) }
                "startswith" -> binaryText(name, args) { a, b -> a.startsWith(b, ignoreCase = true) }
                "endswith" -> binaryText(name, args) { a, b -> a.endsWith(b, ignoreCase = true) }
                "upper" -> {
                    requireCount(name, args, 1)
                    Node.Str(toText(args[0]).uppercase(Locale.ROOT))
                }
                "lower" -> {
                    requireCount(name, args, 1)
                    Node.Str(toText(args[0]).lowercase(Locale.ROOT))
                }
                "concat" -> Node.Str(args.joinToString("") { toText(it) })
                "isnull" -> {
                    requireCount(name, args, 1)
                    Node.Bool(args[0] is Node.Unavailable)
                }
                "isempty" -> {
                    requireCount(name, args, 1)
                    Node.Bool(toText(args[0]).isBlank())
                }
                "isnan" -> {
                    requireCount(name, args, 1)
                    val numeric = toNumeric(args[0])
                    Node.Bool(numeric is NumOr.Num && numeric.value.isNaN())
                }
                "number" -> {
                    requireCount(name, args, 1)
                    numericNode(toNumeric(args[0]))
                }
                "string" -> {
                    requireCount(name, args, 1)
                    Node.Str(toText(args[0]))
                }
                "bool" -> {
                    requireCount(name, args, 1)
                    Node.Bool(toBool(args[0]))
                }
                "percent" -> percent(name, args)
                "between" -> between(name, args)
                else -> throw error(Strings.t("不支持的函数：$name", "Unsupported function: $name"))
            }
        }

        private fun sumNumbers(args: List<Node>): Node {
            val values = numericValues(args) ?: return Node.Unavailable
            return Node.Num(values.sum())
        }

        private fun reduceNumbers(name: String, args: List<Node>, minimum: Int, reduce: (List<Double>) -> Node): Node {
            requireMinCount(name, args, minimum)
            val values = numericValues(args) ?: return Node.Unavailable
            return reduce(values)
        }

        private fun clamp(name: String, args: List<Node>): Node {
            requireCount(name, args, 3)
            val values = numericValues(args) ?: return Node.Unavailable
            val value = values[0]
            val minimum = values[1]
            val maximum = values[2]
            if (minimum > maximum) {
                throw ExpressionFailure(Strings.t("函数 clamp 的最小值不能大于最大值", "Function clamp requires min <= max"))
            }
            return Node.Num(
                when {
                    value < minimum -> minimum
                    value > maximum -> maximum
                    else -> value
                },
            )
        }

        private fun round(name: String, args: List<Node>): Node {
            if (args.size !in 1..2) {
                throw ExpressionFailure(Strings.t("函数 $name 需要 1~2 个参数", "Function $name requires 1-2 arguments"))
            }
            val value = toNumeric(args[0])
            val digits = if (args.size == 2) {
                val raw = toNumeric(args[1])
                when (raw) {
                    is NumOr.Num -> raw.value.toInt().coerceIn(0, 8)
                    NumOr.Unavailable -> return Node.Unavailable
                }
            } else {
                0
            }
            return when (value) {
                is NumOr.Num -> Node.Num(DotNetNumber.roundAwayFromZero(value.value, digits))
                NumOr.Unavailable -> Node.Unavailable
            }
        }

        private fun unaryNumber(name: String, args: List<Node>, apply: (Double) -> Node): Node {
            requireCount(name, args, 1)
            return when (val value = toNumeric(args[0])) {
                is NumOr.Num -> apply(value.value)
                NumOr.Unavailable -> Node.Unavailable
            }
        }

        private fun binaryNumber(name: String, args: List<Node>, apply: (Double, Double) -> Node): Node {
            requireCount(name, args, 2)
            val first = toNumeric(args[0])
            val second = toNumeric(args[1])
            return if (first is NumOr.Num && second is NumOr.Num) apply(first.value, second.value) else Node.Unavailable
        }

        private fun binaryText(name: String, args: List<Node>, test: (String, String) -> Boolean): Node {
            requireCount(name, args, 2)
            return Node.Bool(test(toText(args[0]), toText(args[1])))
        }

        private fun percent(name: String, args: List<Node>): Node {
            requireCount(name, args, 2)
            val values = numericValues(args) ?: return Node.Unavailable
            val total = values[1]
            return if (kotlin.math.abs(total) < Double.MIN_VALUE) Node.Num(0.0) else Node.Num(values[0] / total * 100.0)
        }

        private fun between(name: String, args: List<Node>): Node {
            requireCount(name, args, 3)
            val values = numericValues(args) ?: return Node.Unavailable
            return Node.Bool(values[0] >= values[1] && values[0] <= values[2])
        }

        /** Converts every argument to a number, or reports "unavailable" if any is. */
        private fun numericValues(args: List<Node>): List<Double>? {
            var unavailable = false
            val values = ArrayList<Double>(args.size)
            for (arg in args) {
                when (val value = toNumeric(arg)) {
                    is NumOr.Num -> values.add(value.value)
                    NumOr.Unavailable -> {
                        values.add(0.0)
                        unavailable = true
                    }
                }
            }
            return if (unavailable) null else values
        }

        private fun requireCount(name: String, args: List<Node>, count: Int) {
            if (args.size != count) {
                throw ExpressionFailure(Strings.t("函数 $name 需要 $count 个参数", "Function $name requires $count arguments"))
            }
        }

        private fun requireMinCount(name: String, args: List<Node>, count: Int) {
            if (args.size < count) {
                throw ExpressionFailure(
                    Strings.t("函数 $name 至少需要 $count 个参数", "Function $name requires at least $count arguments"),
                )
            }
        }

        private fun parseNumber(): Double {
            val start = position
            var hasExponent = false
            while (position < text.length) {
                val c = text[position]
                if (c.isDigit() || c == '.') {
                    position++
                    continue
                }
                if ((c == 'e' || c == 'E') && !hasExponent) {
                    hasExponent = true
                    position++
                    if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                    continue
                }
                break
            }
            val raw = text.substring(start, position)
            val value = parseInvariantDouble(raw)
                ?: throw error(Strings.t("无效数字：$raw", "Invalid number: $raw"))
            return value
        }

        private fun parseString(): String {
            val quote = text[position]
            position++
            val chars = StringBuilder()
            while (position < text.length) {
                val c = text[position]
                position++
                if (c == quote) return chars.toString()
                if (c == '\\' && position < text.length) {
                    val escaped = text[position]
                    position++
                    chars.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '\\' -> '\\'
                            '\'' -> '\''
                            '"' -> '"'
                            else -> escaped
                        },
                    )
                } else {
                    chars.append(c)
                }
            }
            throw error(Strings.t("字符串缺少结束引号", "Missing closing quote"))
        }

        private fun parseIdentifier(): String {
            val start = position
            position++
            while (position < text.length && isIdentifierPart(text[position])) position++
            return text.substring(start, position)
        }

        private fun match(token: String): Boolean {
            skipWhite()
            if (position + token.length > text.length) return false
            if (text.regionMatches(position, token, 0, token.length).not()) return false
            position += token.length
            return true
        }

        private fun matchWord(word: String): Boolean {
            skipWhite()
            if (position + word.length > text.length) return false
            if (!text.regionMatches(position, word, 0, word.length, ignoreCase = true)) return false
            val end = position + word.length
            if (end < text.length && isIdentifierPart(text[end])) return false
            if (position > 0 && isIdentifierPart(text[position - 1])) return false
            position = end
            return true
        }

        private fun require(token: String) {
            if (!match(token)) throw error(Strings.t("缺少 '$token'", "Missing '$token'"))
        }

        private fun skipWhite() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        private fun error(message: String): ExpressionFailure = ExpressionFailure(
            Strings.t("表达式错误（位置 ${position + 1}）：$message", "Expression error (position ${position + 1}): $message"),
        )
    }

    /** Wraps a resolver reading into a node; `null` and [MetricValue.Unavailable] are unavailable. */
    private fun fromMetric(value: MetricValue?): Node = when (value) {
        null -> Node.Unavailable
        is MetricValue.Number -> Node.Num(value.value)
        is MetricValue.Text -> Node.Str(value.value)
        is MetricValue.Unavailable -> Node.Unavailable
    }

    /** `ToNumber`: numeric, or the unavailable marker, or an error for a non-numeric text. */
    private fun toNumeric(node: Node): NumOr = when (node) {
        is Node.Num -> NumOr.Num(node.value)
        is Node.Bool -> NumOr.Num(if (node.value) 1.0 else 0.0)
        Node.Unavailable -> NumOr.Unavailable
        is Node.Str -> {
            val number = tryNumber(node)
            if (number == null) {
                throw ExpressionFailure(Strings.t("'${node.value}' 不是数值", "'${node.value}' is not numeric"))
            }
            NumOr.Num(number)
        }
    }

    private fun numericNode(value: NumOr): Node = when (value) {
        is NumOr.Num -> Node.Num(value.value)
        NumOr.Unavailable -> Node.Unavailable
    }

    private fun combine(left: NumOr, right: NumOr, operation: (Double, Double) -> Double): Node =
        if (left is NumOr.Num && right is NumOr.Num) Node.Num(operation(left.value, right.value)) else Node.Unavailable

    /** `TryNumber`: the numeric value of a node, or `null` when it is not numeric (or is `NaN`). */
    private fun tryNumber(node: Node): Double? = when (node) {
        Node.Unavailable -> null
        is Node.Bool -> if (node.value) 1.0 else 0.0
        is Node.Num -> if (node.value.isNaN()) null else node.value
        is Node.Str -> parseInvariantDouble(node.value)?.takeIf { !it.isNaN() }
    }

    /** `ToBool`: booleans, non-zero numbers and the desktop's string truthiness rules. */
    private fun toBool(node: Node): Boolean = when (node) {
        Node.Unavailable -> false
        is Node.Bool -> node.value
        is Node.Num -> node.value.isNaN() || kotlin.math.abs(node.value) > Double.MIN_VALUE
        is Node.Str -> {
            val number = tryNumber(node)
            if (number != null) {
                kotlin.math.abs(number) > Double.MIN_VALUE
            } else {
                val text = node.value.trim()
                when {
                    text.equals("true", ignoreCase = true) -> true
                    text.equals("false", ignoreCase = true) -> false
                    text.isEmpty() -> false
                    text.equals("off", ignoreCase = true) -> false
                    text.equals("no", ignoreCase = true) -> false
                    text == "0" -> false
                    else -> true
                }
            }
        }
    }

    /** `ToText`: .NET's invariant text form (`True`/`False` for booleans, `""` for unavailable). */
    private fun toText(node: Node): String = when (node) {
        Node.Unavailable -> ""
        is Node.Bool -> if (node.value) "True" else "False"
        is Node.Str -> node.value
        is Node.Num -> DotNetNumber.toText(node.value)
    }

    /** `ValuesEqual`: `null == null`, numeric tolerance `1e-7`, boolean and ordinal-ignore-case. */
    private fun valuesEqual(left: Node, right: Node): Boolean {
        if (left is Node.Unavailable || right is Node.Unavailable) {
            return left is Node.Unavailable && right is Node.Unavailable
        }
        val leftNumber = tryNumber(left)
        val rightNumber = tryNumber(right)
        if (leftNumber != null && rightNumber != null) return kotlin.math.abs(leftNumber - rightNumber) < 0.0000001
        if (left is Node.Bool || right is Node.Bool) return toBool(left) == toBool(right)
        return toText(left).equals(toText(right), ignoreCase = true)
    }

    /** `Compare`: numeric when both sides are numeric, ordinal-ignore-case text otherwise. */
    private fun compare(left: Node, right: Node, test: (Int) -> Boolean): Node {
        if (left is Node.Unavailable || right is Node.Unavailable) return Node.Unavailable
        val leftNumber = tryNumber(left)
        val rightNumber = tryNumber(right)
        val order = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            toText(left).compareTo(toText(right), ignoreCase = true)
        }
        return Node.Bool(test(order))
    }

    /**
     * `ExpressionEngine.TryNumber` for a text: invariant parse first, then a comma-decimal
     * tolerant parse, and `NaN` is never numeric.
     */
    private fun parseInvariantDouble(raw: String): Double? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!INVARIANT_NUMBER.matches(text)) return null
        return text.toDoubleOrNull()
    }

    private val INVARIANT_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")
}
