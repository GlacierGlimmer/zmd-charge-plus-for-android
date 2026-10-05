package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Pure helpers shared by the custom HTTP/JSON collector.
 *
 * They live outside the collector so that refresh clamping, exponential backoff, environment
 * substitution and JSON→`MetricValue` mapping can be unit tested without Android or a network.
 */
object HttpSourceMapping {

    /** Desktop parity: `Math.Clamp(source.RefreshSeconds, 5, 86400)`. */
    const val MIN_REFRESH_SECONDS = 5
    const val MAX_REFRESH_SECONDS = 86_400

    /** Upper bound of the exponential backoff, expressed as a multiple of the refresh interval. */
    const val MAX_BACKOFF_MULTIPLIER = 10

    private val ENV_PATTERN = Regex("""\$\{(?:env:)?([A-Za-z_][A-Za-z0-9_]*)\}""")

    /** Clamps a source refresh cadence to 5 s .. 86 400 s (never faster than 5 s). */
    fun refreshIntervalMs(refreshSeconds: Int): Long =
        refreshSeconds.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS) * 1_000L

    /**
     * Delay before the next attempt after [failureCount] consecutive failures of a source whose
     * healthy cadence is [baseIntervalMs]: `base * 2^(n-1)`, capped at `base * 10`.
     *
     * `failureCount <= 0` (a healthy source) returns the base interval. The first failure therefore
     * retries after exactly one interval, the second after two, and so on up to ten.
     */
    fun backoffDelayMs(baseIntervalMs: Long, failureCount: Int): Long {
        val base = baseIntervalMs.coerceAtLeast(1_000L)
        if (failureCount <= 0) return base
        val exponent = (failureCount - 1).coerceAtMost(4)
        val multiplier = 1 shl exponent
        return base * multiplier.coerceAtMost(MAX_BACKOFF_MULTIPLIER)
    }

    /**
     * Substitutes environment references in a header value: `${ENV_NAME}` (Android spelling) and
     * `${env:ENV_NAME}` (desktop ECP spelling) both resolve through [environment]; an unset variable
     * expands to the empty string, exactly like the desktop `ExpandEnvironment` helper.
     */
    fun expandEnvironment(value: String, environment: (String) -> String? = System::getenv): String =
        ENV_PATTERN.replace(value) { match -> environment(match.groupValues[1]) ?: "" }

    /**
     * Maps a JSON node to a metric value without ever fabricating data:
     *  * number → [MetricValue.Number];
     *  * string that parses as a finite number → [MetricValue.Number] (so `"12.50"` formats as a
     *    number in templates), any other string / boolean → [MetricValue.Text];
     *  * object or array → [MetricValue.Text] holding the raw JSON;
     *  * missing path or JSON `null` → [MetricValue.Unavailable] with [UnavailableReason.NO_DATA].
     */
    fun toMetricValue(element: JsonElement?): MetricValue = when (element) {
        null -> MetricValue.Unavailable(UnavailableReason.NO_DATA, "json_path_missing")
        JsonNull -> MetricValue.Unavailable(UnavailableReason.NO_DATA, "json_null")
        is JsonPrimitive -> if (element.isString) {
            val parsed = element.content.toDoubleOrNull()
            if (parsed != null && parsed.isFinite()) MetricValue.Number(parsed) else MetricValue.Text(element.content)
        } else {
            val numeric = element.doubleOrNull
            if (numeric != null && numeric.isFinite()) MetricValue.Number(numeric) else MetricValue.Text(element.content)
        }
        else -> MetricValue.Text(element.toString())
    }
}
