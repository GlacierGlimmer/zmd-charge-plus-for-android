package com.glacierglimmer.endfieldchargeplus.core.template

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Number/text conversion primitives that reproduce the .NET (invariant culture) behaviour the
 * Windows ECP `TemplateEngine` relies on.
 *
 * Everything here was pinned against real .NET output (`dotnet` 10, invariant culture) so that the
 * Kotlin formatter is byte-identical for the formats ECP uses:
 *
 *  * [toText] mirrors `Convert.ToString(double, InvariantCulture)` (`1` not `1.0`, `-0`, `1E+21`,
 *    `1E-07`, `Infinity`, `NaN`).
 *  * [formatCustom] mirrors custom numeric format strings (`0`, `0.0`, `0.00`, `#,##0.00`, `0.##`).
 *    .NET first rounds the value to 15 significant decimal digits (half away from zero) and then
 *    applies the pattern (again half away from zero); that is why `(1.005).ToString("0.00")` is
 *    `1.01` while the standard `F2` format yields `1.00`.
 *  * [formatStandard] mirrors the standard specifiers (`F`, `N`, `P`, `E`, `G`, `R`), which since
 *    .NET Core 3.0 round the exact binary value half-to-even.
 *  * `NaN` renders as `NaN`, the infinities as `Infinity` / `-Infinity`, in every numeric format.
 *
 * Supported custom-pattern subset (everything ECP ships): `0`/`#` placeholders, the decimal point,
 * `,` group separators, trailing `,` ×1000 scaling, `%` and `‰` multipliers, `\`-escaped and
 * `'`/`"`-quoted literals, `;`-separated positive/negative/zero sections and `E+0`/`e-00`
 * scientific notation. Unrecognized characters are copied literally, exactly like .NET.
 *
 * This object is internal to `:core`; the public entry point is [ValueFormatter].
 */
internal object DotNetNumber {

    /** `NumberFormatInfo.InvariantInfo.NaNSymbol`. */
    const val NAN: String = "NaN"

    /** `NumberFormatInfo.InvariantInfo.PositiveInfinitySymbol`. */
    const val POSITIVE_INFINITY: String = "Infinity"

    /** `NumberFormatInfo.InvariantInfo.NegativeInfinitySymbol`. */
    const val NEGATIVE_INFINITY: String = "-Infinity"

    /** True when [value] is one of .NET's three special floating point values. */
    fun isSpecial(value: Double): Boolean = value.isNaN() || value.isInfinite()

    /** The .NET symbol for a special value; only meaningful when [isSpecial] is true. */
    fun specialSymbol(value: Double): String = when {
        value.isNaN() -> NAN
        value > 0 -> POSITIVE_INFINITY
        else -> NEGATIVE_INFINITY
    }

    /**
     * `Convert.ToString(value, CultureInfo.InvariantCulture)`.
     *
     * Uses shortest round-trip digits with .NET's notation thresholds: fixed notation for decimal
     * exponents in `-4..16`, scientific notation (`1E+21`, `1E-07`, always signed, at least two
     * exponent digits) outside that range.
     */
    fun toText(value: Double): String {
        if (isSpecial(value)) return specialSymbol(value)
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val repr = value.toString()
        val eIndex = repr.indexOf('E')
        if (eIndex < 0) {
            // Java prints 1.0 for integral doubles; .NET prints 1.
            return if (repr.endsWith(".0")) repr.dropLast(2) else repr
        }
        val mantissa = repr.substring(0, eIndex)
        val exponent = repr.substring(eIndex + 1).toInt()
        return if (exponent in -4..16) plainFromScientific(mantissa, exponent) else scientificFromJava(mantissa, exponent)
    }

    /** Renders `mantissa × 10^exponent` in fixed notation, trimming trailing zeros. */
    private fun plainFromScientific(mantissa: String, exponent: Int): String {
        val negative = mantissa.startsWith("-")
        val digits = mantissa.removePrefix("-").replace(".", "")
        val pointPos = 1 + exponent
        val builder = StringBuilder()
        if (negative) builder.append('-')
        when {
            pointPos <= 0 -> {
                builder.append("0.")
                repeat(-pointPos) { builder.append('0') }
                builder.append(digits.trimEnd('0').ifEmpty { "0" })
            }
            pointPos >= digits.length -> {
                builder.append(digits)
                repeat(pointPos - digits.length) { builder.append('0') }
            }
            else -> {
                builder.append(digits, 0, pointPos)
                builder.append('.')
                builder.append(digits.substring(pointPos).trimEnd('0').ifEmpty { "0" })
            }
        }
        return builder.toString()
    }

    /** Normalizes Java's `1.0E21` mantissa/exponent pair to .NET's `1E+21` spelling. */
    private fun scientificFromJava(mantissa: String, exponent: Int): String {
        val trimmed = mantissa.trimEnd('0').trimEnd('.')
        val sign = if (exponent < 0) "-" else "+"
        val magnitude = kotlin.math.abs(exponent).toString().padStart(2, '0')
        return trimmed + "E" + sign + magnitude
    }

    /**
     * Rounds like `Math.Round(value, digits, MidpointRounding.AwayFromZero)`.
     *
     * .NET scales by `10^digits`, rounds the *scaled double* half away from zero and divides back;
     * the scaled product is a double, so `2.675 * 100` is exactly `267.5` and rounds to `2.68`,
     * while `1.005 * 100` is `100.49999999999999` and rounds to `1.00`. Custom numeric format
     * strings take a different path (see [formatCustom]), which is why `(1.005).ToString("0.00")`
     * is `1.01` but `Math.Round(1.005, 2, AwayFromZero)` is `1.0`.
     */
    fun roundAwayFromZero(value: Double, digits: Int): Double {
        if (isSpecial(value)) return value
        val scale = digits.coerceIn(0, 15)
        if (scale == 0) return roundHalfAwayFromZero(value)
        val power10 = Math.pow(10.0, scale.toDouble())
        val shifted = value * power10
        if (!shifted.isFinite()) return value
        return roundHalfAwayFromZero(shifted) / power10
    }

    /** Rounds to an integral double, half away from zero (`Math.Round(x, MidpointRounding.AwayFromZero)`). */
    private fun roundHalfAwayFromZero(value: Double): Double = when {
        value.isNaN() || value.isInfinite() -> value
        value >= 0 -> kotlin.math.floor(value + 0.5)
        else -> kotlin.math.ceil(value - 0.5)
    }

    /**
     * `value.ToString("F<decimals>", InvariantCulture)`: exact binary value, round half to even,
     * always exactly [decimals] fractional digits (negative zero keeps its sign).
     */
    fun formatFixed(value: Double, decimals: Int): String {
        if (isSpecial(value)) return specialSymbol(value)
        return BigDecimal(value).setScale(decimals.coerceIn(0, 99), RoundingMode.HALF_EVEN).toPlainString()
    }

    /** `value.ToString("N<decimals>", InvariantCulture)`: like F with `,` group separators. */
    fun formatGrouped(value: Double, decimals: Int): String {
        if (isSpecial(value)) return specialSymbol(value)
        val rounded = BigDecimal(value).setScale(decimals.coerceIn(0, 99), RoundingMode.HALF_EVEN)
        val plain = rounded.abs().toPlainString()
        val dot = plain.indexOf('.')
        val intPart = if (dot < 0) plain else plain.substring(0, dot)
        val rest = if (dot < 0) "" else plain.substring(dot)
        val grouped = group(intPart)
        return (if (rounded.signum() < 0) "-" else "") + grouped + rest
    }

    /**
     * `value.ToString("<spec>", InvariantCulture)` for the standard specifiers.
     *
     * Returns `null` when the specifier is not applicable to `Double` (`D`, `X`, ...), which is
     * exactly the `FormatException` the desktop code catches and replaces with `0.##`.
     */
    fun formatStandard(value: Double, spec: String): String? {
        if (spec.isEmpty()) return null
        val letter = spec[0].uppercaseChar()
        val digits = if (spec.length > 1) spec.substring(1).toIntOrNull() ?: return null else null
        return when (letter) {
            'F' -> formatFixed(value, digits ?: 2)
            'N' -> formatGrouped(value, digits ?: 2)
            'P' -> if (isSpecial(value)) specialSymbol(value) else formatFixed(value * 100.0, digits ?: 2) + " %"
            'E' -> formatScientific(value, digits ?: 6)
            'G' -> if (digits == null) toText(value) else formatGeneral(value, digits)
            'R' -> toText(value)
            else -> null
        }
    }

    /** `value.ToString("E<digits>")`: mantissa with [digits] decimals and a three-digit exponent. */
    private fun formatScientific(value: Double, digits: Int): String {
        if (isSpecial(value)) return specialSymbol(value)
        if (value == 0.0) return "0." + "0".repeat(digits.coerceAtLeast(0)) + "E+000"
        val exponent = kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(value))).toInt()
        val mantissa = value / Math.pow(10.0, exponent.toDouble())
        return formatFixed(mantissa, digits) + "E" + exponentSign(exponent) + exponentMagnitude(exponent, 3)
    }

    /** `value.ToString("G<digits>")` with an explicit precision. */
    private fun formatGeneral(value: Double, digits: Int): String {
        if (isSpecial(value)) return specialSymbol(value)
        val precision = digits.coerceIn(1, 17)
        val rounded = significant(value, precision) ?: return toText(value)
        if (rounded.signum() == 0) return "0"
        val exponent = kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(rounded.toDouble()))).toInt()
        return if (exponent < -4 || exponent >= precision) {
            formatScientific(rounded.toDouble(), 0)
        } else {
            rounded.stripTrailingZeros().toPlainString()
        }
    }

    /**
     * `value.ToString(pattern, InvariantCulture)` for custom numeric format strings, including
     * section lists (`positive;negative;zero`). Never throws.
     */
    fun formatCustom(value: Double, pattern: String): String {
        val sections = splitSections(pattern)
        val section = when {
            value < 0 && sections.size >= 2 -> sections[1]
            value == 0.0 && sections.size >= 3 -> sections[2]
            else -> sections[0]
        }
        if (section.isEmpty() && sections.size > 1) return ""
        return formatSection(value, section, autoSign = sections.size < 2)
    }

    /** Splits a custom format on top-level `;`, honouring `\` escapes and quotes. */
    private fun splitSections(pattern: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                    i++
                }
                c == '\\' -> {
                    current.append(c)
                    if (i + 1 < pattern.length) current.append(pattern[i + 1])
                    i += if (i + 1 < pattern.length) 2 else 1
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                    i++
                }
                c == ';' -> {
                    out.add(current.toString())
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        out.add(current.toString())
        return out
    }

    /** One element of a parsed custom-format section. */
    private sealed interface Token {
        /** Literal text emitted verbatim. */
        data class Literal(val text: String) : Token

        /** `0` (required) or `#` (optional) integer digit placeholder. */
        data class IntPlaceholder(val required: Boolean) : Token

        /** `0` (required) or `#` (optional) fractional digit placeholder. */
        data class DecPlaceholder(val required: Boolean) : Token

        /** `,` acting as a group separator. */
        data object Group : Token

        /** `,` acting as a ×1000 scaler. */
        data object Scale : Token

        /** `.` before the fractional placeholders. */
        data object DecimalPoint : Token

        /** `E+0` style scientific notation. */
        data class Scientific(val exponentDigits: Int, val upper: Boolean) : Token
    }

    private fun formatSection(value: Double, section: String, autoSign: Boolean): String {
        val tokens = parseSection(section)
        val intPlaceholders = tokens.filterIsInstance<Token.IntPlaceholder>()
        val decPlaceholders = tokens.filterIsInstance<Token.DecPlaceholder>()
        val scientific = tokens.filterIsInstance<Token.Scientific>().firstOrNull()

        if (isSpecial(value)) {
            val prefix = literalText(tokens, suffix = false)
            val suffix = literalText(tokens, suffix = true)
            return prefix + specialSymbol(value) + suffix
        }

        var working = value
        repeat(tokens.count { it is Token.Scale }) { working /= 1000.0 }
        working *= Math.pow(100.0, countChar(section, '%').toDouble())
        working *= Math.pow(1000.0, countChar(section, '‰').toDouble())

        if (scientific != null) return formatScientificSection(tokens, working, scientific)

        val decCount = decPlaceholders.size
        val rounded = significant(working, 15)?.setScale(decCount, RoundingMode.HALF_UP)
            ?: return specialSymbol(working)
        val negative = rounded.signum() < 0
        val integerDigits = rounded.abs().toBigInteger().toString()
        val paddedInteger = integerDigits.padStart(intPlaceholders.count { it.required }, '0')
        val groupedInteger = if (tokens.any { it is Token.Group }) group(paddedInteger) else paddedInteger

        val fraction = rounded.abs().toPlainString().substringAfter('.', "").padEnd(decCount, '0')
        var lastFractionIndex = -1
        for (i in decPlaceholders.indices) {
            if (decPlaceholders[i].required || fraction.getOrElse(i) { '0' } != '0') lastFractionIndex = i
        }

        val lastIntIndex = tokens.indexOfLast { it is Token.IntPlaceholder }
        val out = StringBuilder()
        if (negative && autoSign) out.append('-')
        var decimalSeen = 0
        for ((index, token) in tokens.withIndex()) {
            when (token) {
                is Token.Literal -> out.append(token.text)
                is Token.IntPlaceholder -> if (index == lastIntIndex) out.append(groupedInteger)
                is Token.DecPlaceholder -> {
                    val i = decimalSeen
                    decimalSeen++
                    if (i <= lastFractionIndex) out.append(fraction.getOrElse(i) { '0' })
                }
                Token.DecimalPoint -> if (lastFractionIndex >= 0) out.append('.')
                Token.Group, Token.Scale, is Token.Scientific -> Unit
            }
        }
        return out.toString()
    }

    /** Renders a section that contains an `E+0` / `e-00` scientific specifier. */
    private fun formatScientificSection(tokens: List<Token>, value: Double, sci: Token.Scientific): String {
        val intPlaceholders = tokens.filterIsInstance<Token.IntPlaceholder>()
        val decPlaceholders = tokens.filterIsInstance<Token.DecPlaceholder>()
        val exponent = if (value == 0.0) 0 else kotlin.math.floor(kotlin.math.log10(kotlin.math.abs(value))).toInt()
        val mantissa = if (value == 0.0) 0.0 else value / Math.pow(10.0, exponent.toDouble())
        val rounded = significant(mantissa, 15)?.setScale(decPlaceholders.size, RoundingMode.HALF_UP)
            ?: return specialSymbol(value)
        val fraction = rounded.abs().toPlainString().substringAfter('.', "").padEnd(decPlaceholders.size, '0')
        val lastIntIndex = tokens.indexOfLast { it is Token.IntPlaceholder }
        val out = StringBuilder()
        var decimalSeen = 0
        for ((index, token) in tokens.withIndex()) {
            when (token) {
                is Token.Literal -> out.append(token.text)
                is Token.IntPlaceholder -> if (index == lastIntIndex) {
                    out.append(rounded.abs().toBigInteger().toString().padStart(intPlaceholders.count { it.required }, '0'))
                }
                is Token.DecPlaceholder -> {
                    val i = decimalSeen
                    decimalSeen++
                    out.append(fraction.getOrElse(i) { '0' })
                }
                Token.DecimalPoint -> if (decPlaceholders.isNotEmpty()) out.append('.')
                is Token.Scientific -> {
                    out.append(if (sci.upper) 'E' else 'e')
                    out.append(exponentSign(exponent))
                    // .NET always writes at least three exponent digits (0.00E+00 -> E+000).
                    out.append(exponentMagnitude(exponent, sci.exponentDigits.coerceAtLeast(3)))
                }
                Token.Group, Token.Scale -> Unit
            }
        }
        return out.toString()
    }

    /** Literal text before the first placeholder, or after the last one when [suffix] is true. */
    private fun literalText(tokens: List<Token>, suffix: Boolean): String {
        val firstPlaceholder = tokens.indexOfFirst {
            it is Token.IntPlaceholder || it is Token.DecPlaceholder
        }
        if (firstPlaceholder < 0) return tokens.filterIsInstance<Token.Literal>().joinToString("") { it.text }
        val range = if (suffix) (firstPlaceholder + 1) until tokens.size else 0 until firstPlaceholder
        return range.filter { tokens[it] is Token.Literal }.joinToString("") { (tokens[it] as Token.Literal).text }
    }

    private fun parseSection(section: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        var inFraction = false
        var seenDecimalPoint = false
        while (i < section.length) {
            val c = section[i]
            when {
                c == '\\' && i + 1 < section.length -> {
                    tokens.add(Token.Literal(section[i + 1].toString()))
                    i += 2
                }
                c == '\'' || c == '"' -> {
                    val end = section.indexOf(c, i + 1)
                    tokens.add(Token.Literal(if (end < 0) section.substring(i + 1) else section.substring(i + 1, end)))
                    i = if (end < 0) section.length else end + 1
                }
                (c == 'E' || c == 'e') && isScientificTail(section, i) -> {
                    var j = i + 1
                    if (section[j] == '+' || section[j] == '-') j++
                    var digits = 0
                    while (j < section.length && section[j] == '0') {
                        digits++
                        j++
                    }
                    tokens.add(Token.Scientific(digits.coerceAtLeast(1), c == 'E'))
                    i = j
                }
                c == '0' || c == '#' -> {
                    if (inFraction) tokens.add(Token.DecPlaceholder(c == '0')) else tokens.add(Token.IntPlaceholder(c == '0'))
                    i++
                }
                c == '.' && !seenDecimalPoint -> {
                    seenDecimalPoint = true
                    inFraction = true
                    tokens.add(Token.DecimalPoint)
                    i++
                }
                c == ',' -> {
                    val hasPlaceholderBefore = tokens.any { it is Token.IntPlaceholder }
                    val hasPlaceholderAfter = section.substring(i + 1).any { it == '0' || it == '#' }
                    tokens.add(if (hasPlaceholderBefore && hasPlaceholderAfter) Token.Group else Token.Scale)
                    i++
                }
                else -> {
                    tokens.add(Token.Literal(c.toString()))
                    i++
                }
            }
        }
        return tokens
    }

    private fun isScientificTail(section: String, index: Int): Boolean {
        var j = index + 1
        if (j < section.length && (section[j] == '+' || section[j] == '-')) j++
        if (j >= section.length || section[j] != '0') return false
        while (j < section.length && section[j] == '0') j++
        return j >= section.length
    }

    /** Inserts the invariant group separator every three digits. */
    private fun group(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

    private fun exponentSign(exponent: Int): String = if (exponent < 0) "-" else "+"

    private fun exponentMagnitude(exponent: Int, minDigits: Int): String =
        kotlin.math.abs(exponent).toString().padStart(minDigits, '0')

    private fun countChar(text: String, target: Char): Int {
        var count = 0
        var i = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\\' -> i++
                c == '\'' || c == '"' -> quote = c
                c == target -> count++
            }
            i++
        }
        return count
    }

    /**
     * Rounds to [digits] significant decimal digits half away from zero, the intermediate step .NET
     * performs before applying a custom numeric pattern. Returns `null` for special values.
     */
    fun significant(value: Double, digits: Int): BigDecimal? {
        if (isSpecial(value)) return null
        return BigDecimal(value).round(MathContext(digits.coerceIn(1, 17), RoundingMode.HALF_UP))
    }
}
