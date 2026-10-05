package com.glacierglimmer.endfieldchargeplus.ui.state

import java.util.Locale
import kotlin.math.roundToInt

/** Small formatting/parsing helpers shared by the settings screens. */
object UiFormatting {

    /** `6.0` → `"6"`, `0.275` → `"0.275"`; never uses scientific notation for UI ranges. */
    fun numberText(value: Double, decimals: Int = 3): String {
        if (!value.isFinite()) return "0"
        val rounded = String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
        return rounded.ifEmpty { "0" }
    }

    fun percentText(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

    fun parseDouble(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    fun parseInt(text: String): Int? = text.trim().toIntOrNull()

    /** `#RRGGBB` or `#AARRGGBB`, or null when the text is not a colour. */
    fun parseColor(text: String): Long? {
        val value = text.trim()
        if (!value.startsWith("#")) return null
        val hex = value.substring(1)
        if (hex.length != 6 && hex.length != 8) return null
        if (hex.any { it.digitToIntOrNull(16) == null }) return null
        return hex.toLong(16)
    }

    /** True when [text] is a syntactically valid hex colour. */
    fun isColor(text: String): Boolean = parseColor(text) != null

    /** True when [text] parses as a finite number. */
    fun isNumber(text: String): Boolean = parseDouble(text)?.isFinite() == true

    /** Clamps a parsed value into an inclusive range; returns [fallback] when unparsable. */
    fun clampDouble(text: String, min: Double, max: Double, fallback: Double): Double {
        val parsed = parseDouble(text) ?: return fallback
        return parsed.coerceIn(min, max)
    }

    /** Clamps a parsed integer into an inclusive range; returns [fallback] when unparsable. */
    fun clampInt(text: String, min: Int, max: Int, fallback: Int): Int {
        val parsed = parseInt(text) ?: return fallback
        return parsed.coerceIn(min, max)
    }

    /**
     * Decimal-SI byte rate, matching the engine's network presentation: `KB/s` below 1 MB/s,
     * `MB/s` above it.
     */
    fun bytesPerSecondText(bytesPerSecond: Double?): String {
        if (bytesPerSecond == null || !bytesPerSecond.isFinite() || bytesPerSecond < 0) return "—"
        return if (bytesPerSecond < 1_000_000.0) {
            String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1_000.0)
        } else {
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        }
    }
}
