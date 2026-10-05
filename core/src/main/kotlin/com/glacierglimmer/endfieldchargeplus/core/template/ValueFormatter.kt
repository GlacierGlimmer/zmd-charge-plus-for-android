package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The `{variable|format}` format pipeline of the ECP template engine.
 *
 * A format string is a `|`-separated **chain** of tokens applied left to right, exactly like
 * `TemplateEngine.FormatPipeline` in the Windows edition. Every token the desktop engine supports
 * is implemented here:
 *
 * | Token | Meaning | Example |
 * | --- | --- | --- |
 * | *empty* / none | .NET custom format `0.##` | `1.5` -> `1.5`, `1.0` -> `1` |
 * | `0`, `0.0`, `0.00`, `#,##0.00` | .NET custom numeric pattern (15 significant digits, half away from zero) | `{cpu.frequency_ghz\|0.00}` |
 * | `F2`, `N0`, `P1`, `E2`, `G` | .NET standard specifiers (exact value, half to even) | `2.5\|F0` -> `2` |
 * | `bytes` | binary human bytes with 1 decimal | `1536\|bytes` -> `1.5 KB` |
 * | `speed` | binary human bytes per second with 1 decimal | `1536\|speed` -> `1.5 KB/s` |
 * | `gb[:n]`, `mb[:n]`, `kb[:n]`, `tb[:n]` | binary unit conversion, `n` decimals (defaults 1/1/1/2) | `{memory.used_bytes\|gb:1}` |
 * | `mbps[:n]`, `kbps[:n]` | meant as **decimal** SI bit rates, but the desktop `mb`/`kb` prefix tests run first, so these tokens are unreachable and the binary unit wins (`1258291\|mbps:1` -> `1.2`) |
 * | `percent[:n]` | `F{n}` + `%` (default 0 decimals) | `20.7\|percent` -> `21%` |
 * | `duration` | `mm:ss`, or `hh:mm:ss` from one hour | `3661\|duration` -> `01:01:01` |
 * | `duration-long` | `d hh:mm:ss` / `天 hh:mm:ss` from one day | `90061\|duration-long` -> `1d 01:01:01` |
 * | `math:add\|sub\|mul\|div\|round\|floor\|ceil\|abs[:arg]` | numeric transform | `20\|math:add:5` -> `25` |
 * | `sub:start[:length]` | substring | `abcdef\|sub:1:3` -> `bcd` |
 * | `replace:old:new` | ordinal replace (new may contain `:`) | `a-b\|replace:-:+` -> `a+b` |
 * | `upper`, `lower` | invariant case folding | `abc\|upper` -> `ABC` |
 * | `time:relative` | "3分钟前" / "3m ago" or a numeric duration | |
 * | `time:<fmt>` | date-time formatting for date-valued texts | `2026-10-05T15:40:35\|time:HH:mm` -> `15:40` |
 * | `auto[:n]` | key-driven unit pick (bytes / speed / duration / percent) | recommended by the catalog |
 *
 * Fallbacks match the desktop engine: an unknown numeric pattern is treated as .NET custom format
 * text, an inapplicable standard specifier falls back to `0.##`, `NaN` renders as `NaN` and the
 * infinities as `Infinity` / `-Infinity`.
 */
object ValueFormatter {

    /**
     * Formats [value] with [spec].
     *
     * A [MetricValue.Unavailable] renders as [TemplateEngine.UNKNOWN_SENTINEL] (`--`), never as a
     * fabricated `0`. A [MetricValue.Text] is passed through unchanged when a numeric format is
     * requested; text tokens (`sub:`, `replace:`, `upper`, `lower`) still apply. This mirrors the
     * Linux/macOS editions, which write a localized status string into a numeric slot (for example
     * a failed probe rendering as `超时`) and must never coerce it to a number.
     */
    fun format(value: MetricValue, spec: String?): String = when (value) {
        is MetricValue.Number -> formatNumber(value.value, spec)
        is MetricValue.Text -> formatText(value.value, spec)
        is MetricValue.Unavailable -> TemplateEngine.UNKNOWN_SENTINEL
    }

    /**
     * Formats a numeric reading. Equivalent to `TemplateEngine.FormatPipeline(value, spec, key)`
     * with an empty key, so `auto:n` falls back to its plain `F{n}` branch.
     */
    fun formatNumber(value: Double, spec: String?): String = pipeline(PipelineValue.Number(value), spec, "")

    /**
     * Formats a textual reading. Text tokens always apply; numeric tokens are ignored so a status
     * string is never turned into a number; `time:` follows the desktop behaviour (a date is
     * formatted, anything else renders the sentinel).
     */
    fun formatText(text: String, spec: String?): String = pipeline(PipelineValue.Text(text), spec, "")

    /**
     * Internal key-aware entry point used by [TemplateEngine]; the variable key matters for the
     * `auto` token, whose branch is chosen from the key and not from the value.
     */
    internal fun formatWithKey(value: MetricValue, spec: String?, key: String): String = when (value) {
        is MetricValue.Number -> pipeline(PipelineValue.Number(value.value), spec, key)
        is MetricValue.Text -> pipeline(PipelineValue.Text(value.value), spec, key)
        is MetricValue.Unavailable -> TemplateEngine.UNKNOWN_SENTINEL
    }

    /**
     * Internal key-aware entry point used by [TemplateEngine] for `{= expression | format}`
     * tokens, whose `auto` branch is decided by the expression text (desktop behaviour).
     */
    internal fun formatNumberWithKey(value: Double, spec: String?, key: String): String =
        pipeline(PipelineValue.Number(value), spec, key)

    /** The value flowing through the pipeline: a number or a string. */
    private sealed interface PipelineValue {
        data class Number(val value: Double) : PipelineValue
        data class Text(val value: String) : PipelineValue
    }

    private fun pipeline(initial: PipelineValue, spec: String?, key: String): String {
        if (spec.isNullOrBlank()) return render(baseFormat(initial, "", key))
        var current = initial
        for (token in spec.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val value = current
            var handled = false
            if (value is PipelineValue.Number) {
                val math = tryMath(value.value, token)
                if (math != null) {
                    current = math
                    handled = true
                }
            }
            if (!handled) {
                val text = tryText(render(value), token)
                if (text != null) {
                    current = text
                    handled = true
                }
            }
            if (!handled) {
                val time = tryTime(value, token)
                if (time != null) {
                    current = time
                    handled = true
                }
            }
            if (handled) continue
            if (token.startsWith("auto", ignoreCase = true)) {
                current = smartAuto(value, key, parseDigits(token, 1))
                continue
            }
            current = baseFormat(value, token, key)
        }
        return render(current)
    }

    /** A numeric transform token, or `null` when [token] is not a `math:` token. */
    private fun tryMath(value: Double, token: String): PipelineValue.Number? {
        if (!token.startsWith("math:", ignoreCase = true)) return null
        val parts = token.split(':')
        val op = if (parts.size > 1) parts[1].lowercase(Locale.ROOT) else ""
        val arg = if (parts.size > 2) parts[2].toDoubleOrNull() ?: 0.0 else 0.0
        return when (op) {
            "add" -> PipelineValue.Number(value + arg)
            "sub" -> PipelineValue.Number(value - arg)
            "mul" -> PipelineValue.Number(value * arg)
            "div" -> PipelineValue.Number(if (kotlin.math.abs(arg) < Double.MIN_VALUE) Double.NaN else value / arg)
            "round" -> PipelineValue.Number(
                DotNetNumber.roundAwayFromZero(value, if (parts.size > 2) parts[2].toIntOrNull()?.coerceIn(0, 8) ?: 0 else 0),
            )
            "floor" -> PipelineValue.Number(kotlin.math.floor(value))
            "ceil" -> PipelineValue.Number(kotlin.math.ceil(value))
            "abs" -> PipelineValue.Number(kotlin.math.abs(value))
            else -> PipelineValue.Number(value)
        }
    }

    /** A text transform token, or `null` when [token] is not one. */
    private fun tryText(value: String, token: String): PipelineValue.Text? {
        if (token.startsWith("sub:", ignoreCase = true)) {
            val parts = token.split(':')
            val start = (if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0).coerceAtLeast(0)
            val length = if (parts.size > 2) {
                (parts[2].toIntOrNull() ?: 0).coerceAtLeast(0)
            } else {
                (value.length - start).coerceAtLeast(0)
            }
            val text = if (start >= value.length) "" else value.substring(start, (start + length).coerceAtMost(value.length))
            return PipelineValue.Text(text)
        }
        if (token.startsWith("replace:", ignoreCase = true)) {
            val parts = token.split(':', limit = 3)
            return PipelineValue.Text(if (parts.size == 3) value.replace(parts[1], parts[2]) else value)
        }
        if (token.equals("upper", ignoreCase = true)) return PipelineValue.Text(value.uppercase(Locale.ROOT))
        if (token.equals("lower", ignoreCase = true)) return PipelineValue.Text(value.lowercase(Locale.ROOT))
        return null
    }

    /**
     * A `time:` token, or `null` when [token] is not one.
     *
     * `time:relative` renders a relative phrase for a date and a plain unit duration for a number;
     * anything else renders [TemplateEngine.UNKNOWN_SENTINEL], exactly like the desktop engine.
     */
    private fun tryTime(value: PipelineValue, token: String): PipelineValue.Text? {
        if (!token.startsWith("time:", ignoreCase = true)) return null
        val argument = token.substring(5)
        if (argument.equals("relative", ignoreCase = true)) {
            val parsed = (value as? PipelineValue.Text)?.let { parseDateTime(it.value) }
            return when {
                parsed != null -> PipelineValue.Text(relativeTime(parsed))
                value is PipelineValue.Number && value.value.isFinite() ->
                    PipelineValue.Text(relativeDuration(value.value))
                else -> PipelineValue.Text(TemplateEngine.UNKNOWN_SENTINEL)
            }
        }
        val parsed = (value as? PipelineValue.Text)?.let { parseDateTime(it.value) }
        return PipelineValue.Text(parsed?.let { formatDateTime(it, argument) } ?: TemplateEngine.UNKNOWN_SENTINEL)
    }

    /** `auto` / `auto:n`: the branch is chosen from the variable [key], not from the value. */
    private fun smartAuto(value: PipelineValue, key: String, digitsInput: Int): PipelineValue {
        if (value !is PipelineValue.Number) return value
        val digits = digitsInput.coerceIn(0, 6)
        val n = value.value
        val lowerKey = key.lowercase(Locale.ROOT)
        return when {
            lowerKey.endsWith("_bps") || lowerKey.contains(".read_bps") || lowerKey.contains(".write_bps") ||
                lowerKey.contains(".io_bps") -> PipelineValue.Text(humanBytes(n, perSecond = true, digits = digits))
            lowerKey.endsWith("_bytes") || lowerKey.contains("bytes") ->
                PipelineValue.Text(humanBytes(n, perSecond = false, digits = digits))
            lowerKey.endsWith("_seconds") -> PipelineValue.Text(duration(n))
            lowerKey.contains("percent") || lowerKey.endsWith(".usage") || lowerKey.endsWith(".progress") ->
                PipelineValue.Text(DotNetNumber.formatFixed(n, digits) + "%")
            else -> PipelineValue.Text(DotNetNumber.formatFixed(n, digits))
        }
    }

    /** `TemplateEngine.BaseFormat`: units, durations and .NET numeric patterns. */
    private fun baseFormat(value: PipelineValue, format: String, key: String): PipelineValue {
        if (value is PipelineValue.Text) {
            // A text reading is never reformatted as a number (see [format]).
            return value
        }
        val n = (value as PipelineValue.Number).value
        if (format.isBlank()) return PipelineValue.Text(DotNetNumber.formatCustom(n, "0.##"))
        val lower = format.lowercase(Locale.ROOT)
        return when {
            lower == "bytes" -> PipelineValue.Text(humanBytes(n, perSecond = false, digits = 1))
            lower == "speed" -> PipelineValue.Text(humanBytes(n, perSecond = true, digits = 1))
            lower.startsWith("gb") -> PipelineValue.Text(DotNetNumber.formatFixed(n / 1024.0.pow(3), parseDigits(format, 1)))
            lower.startsWith("mb") -> PipelineValue.Text(DotNetNumber.formatFixed(n / 1024.0.pow(2), parseDigits(format, 1)))
            lower.startsWith("kb") -> PipelineValue.Text(DotNetNumber.formatFixed(n / 1024.0, parseDigits(format, 1)))
            lower.startsWith("tb") -> PipelineValue.Text(DotNetNumber.formatFixed(n / 1024.0.pow(4), parseDigits(format, 2)))
            lower.startsWith("mbps") -> PipelineValue.Text(DotNetNumber.formatFixed(n * 8.0 / 1_000_000.0, parseDigits(format, 1)))
            lower.startsWith("kbps") -> PipelineValue.Text(DotNetNumber.formatFixed(n * 8.0 / 1_000.0, parseDigits(format, 1)))
            lower.startsWith("percent") -> PipelineValue.Text(DotNetNumber.formatFixed(n, parseDigits(format, 0)) + "%")
            lower == "duration" -> PipelineValue.Text(duration(n))
            lower == "duration-long" -> PipelineValue.Text(durationLong(n))
            else -> PipelineValue.Text(dotNetNumeric(n, format))
        }
    }

    /**
     * `value.ToString(format, InvariantCulture)`: standard specifiers first, custom patterns
     * otherwise, falling back to `0.##` for specifiers that are invalid for `Double` (`.NET`
     * throws `FormatException`, which the desktop engine catches).
     */
    private fun dotNetNumeric(value: Double, format: String): String {
        if (isStandardSpecifier(format)) {
            DotNetNumber.formatStandard(value, format)?.let { return it }
            return DotNetNumber.formatCustom(value, "0.##")
        }
        return DotNetNumber.formatCustom(value, format)
    }

    /** True for `.NET` standard numeric specifiers applicable to a single `Double` value. */
    private fun isStandardSpecifier(format: String): Boolean {
        if (format.length !in 1..3) return false
        val letter = format[0]
        if (letter !in "CcDdEeFfGgNnPpRrXx") return false
        return format.length == 1 || format.substring(1).all { it.isDigit() }
    }

    /** `TemplateEngine.ParseDigits`: the part after the first `:`, clamped to `0..6`. */
    private fun parseDigits(format: String, fallback: Int): Int {
        val parts = format.split(':', limit = 2)
        if (parts.size != 2) return fallback
        return parts[1].toIntOrNull()?.coerceIn(0, 6) ?: fallback
    }

    /** `TemplateEngine.HumanBytes`: binary scaling with `B`, `KB`, `MB`, `GB`, `TB`, `PB`. */
    private fun humanBytes(bytesInput: Double, perSecond: Boolean, digits: Int): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var bytes = bytesInput
        var index = 0
        while (kotlin.math.abs(bytes) >= 1024 && index < units.size - 1) {
            bytes /= 1024.0
            index++
        }
        return DotNetNumber.formatFixed(bytes, digits) + " " + units[index] + if (perSecond) "/s" else ""
    }

    /** `TemplateEngine.Duration`: `mm:ss`, or `hh:mm:ss` once the total is at least an hour. */
    private fun duration(seconds: Double): String {
        val total = wholeSeconds(seconds)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours >= 1) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    /** `TemplateEngine.DurationLong`: adds days and localizes the day unit. */
    private fun durationLong(seconds: Double): String {
        val total = wholeSeconds(seconds)
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (days >= 1) {
            val unit = Strings.t("天", "d")
            "$days$unit %02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        }
    }

    /** `Math.Max(0, (long)seconds)`; non-finite inputs behave like the desktop zero fallback. */
    private fun wholeSeconds(seconds: Double): Long = if (seconds.isFinite()) {
        seconds.toLong().coerceAtLeast(0L)
    } else {
        0L
    }

    /** `TemplateEngine.RelativeTime`: localized "n units ago" / "in n units". */
    private fun relativeTime(moment: LocalDateTime): String {
        val delta = java.time.Duration.between(moment, LocalDateTime.now())
        val future = delta.seconds < 0
        val absolute = delta.abs()
        val text = if (Strings.isEnglish()) {
            val unit = when {
                absolute.seconds < 60 -> "${kotlin.math.max(1, absolute.seconds.toInt())}s"
                absolute.toMinutes() < 60 -> "${absolute.toMinutes()}m"
                absolute.toHours() < 24 -> "${absolute.toHours()}h"
                absolute.toDays() < 30 -> "${absolute.toDays()}d"
                absolute.toDays() < 365 -> "${absolute.toDays() / 30}mo"
                else -> "${absolute.toDays() / 365}y"
            }
            if (future) "in $unit" else "$unit ago"
        } else {
            val unit = when {
                absolute.seconds < 60 -> "${kotlin.math.max(1, absolute.seconds.toInt())}秒"
                absolute.toMinutes() < 60 -> "${absolute.toMinutes()}分钟"
                absolute.toHours() < 24 -> "${absolute.toHours()}小时"
                absolute.toDays() < 30 -> "${absolute.toDays()}天"
                absolute.toDays() < 365 -> "${absolute.toDays() / 30}个月"
                else -> "${absolute.toDays() / 365}年"
            }
            if (future) "${unit}后" else "${unit}前"
        }
        return text
    }

    /** `TemplateEngine.RelativeDuration`: a bare localized duration for a numeric `time:relative`. */
    private fun relativeDuration(seconds: Double): String {
        val absolute = kotlin.math.abs(seconds)
        return if (Strings.isEnglish()) {
            when {
                absolute < 60 -> "${absolute.toInt()}s"
                absolute < 3600 -> "${(absolute / 60).toInt()}m"
                absolute < 86400 -> "${(absolute / 3600).toInt()}h"
                else -> "${(absolute / 86400).toInt()}d"
            }
        } else {
            when {
                absolute < 60 -> "${absolute.toInt()}秒"
                absolute < 3600 -> "${(absolute / 60).toInt()}分钟"
                absolute < 86400 -> "${(absolute / 3600).toInt()}小时"
                else -> "${(absolute / 86400).toInt()}天"
            }
        }
    }

    /**
     * Parses the date-time spellings ECP collects: ISO local date-time, `yyyy-MM-dd HH:mm:ss`,
     * `yyyy/MM/dd HH:mm:ss`, a bare date and a bare time (today's date). Anything else is
     * unparsable and therefore not a date value.
     */
    private fun parseDateTime(text: String): LocalDateTime? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDateTime.parse(trimmed)
        } catch (_: java.time.format.DateTimeParseException) {
            null
        } ?: parseWith(trimmed, "yyyy-MM-dd HH:mm:ss")
            ?: parseWith(trimmed, "yyyy/MM/dd HH:mm:ss")
            ?: parseWith(trimmed, "yyyy-MM-dd HH:mm")
            ?: try {
                LocalDate.parse(trimmed).atStartOfDay()
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
            ?: try {
                LocalTime.parse(trimmed).atDate(LocalDate.now())
            } catch (_: java.time.format.DateTimeParseException) {
                null
            }
    }

    private fun parseWith(text: String, pattern: String): LocalDateTime? = try {
        LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ROOT))
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }

    /**
     * Formats a date with the subset of .NET date patterns ECP uses (`yyyy`, `MM`, `dd`, `HH`,
     * `mm`, `ss`, `fff`, `tt`, `h`, `G`). An unsupported pattern falls back to the general
     * rendering, mirroring the desktop `catch` branch.
     */
    private fun formatDateTime(moment: LocalDateTime, pattern: String): String {
        if (pattern.isEmpty() || pattern == "G") {
            return moment.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
        }
        val translated = buildString {
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("yyyy", i) -> {
                        append("yyyy")
                        i += 4
                    }
                    pattern.startsWith("fff", i) -> {
                        append("SSS")
                        i += 3
                    }
                    pattern.startsWith("MM", i) -> {
                        append("MM")
                        i += 2
                    }
                    pattern.startsWith("dd", i) -> {
                        append("dd")
                        i += 2
                    }
                    pattern.startsWith("HH", i) -> {
                        append("HH")
                        i += 2
                    }
                    pattern.startsWith("mm", i) -> {
                        append("mm")
                        i += 2
                    }
                    pattern.startsWith("ss", i) -> {
                        append("ss")
                        i += 2
                    }
                    pattern.startsWith("tt", i) -> {
                        append("a")
                        i += 2
                    }
                    else -> {
                        append(pattern[i])
                        i++
                    }
                }
            }
        }
        return try {
            moment.format(DateTimeFormatter.ofPattern(translated, Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            moment.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
        }
    }

    /** `Convert.ToString(value, InvariantCulture)`. */
    private fun dotNetText(value: Double): String = DotNetNumber.toText(value)

    /** `Convert.ToString(current, InvariantCulture) ?? ""` at the end of the pipeline. */
    private fun render(value: PipelineValue): String = when (value) {
        is PipelineValue.Number -> DotNetNumber.toText(value.value)
        is PipelineValue.Text -> value.value
    }

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
}
