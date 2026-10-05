package com.glacierglimmer.endfieldchargeplus.core.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * An immutable snapshot of every variable known at one instant.
 *
 * The HUD renderer, the island providers and the settings preview all read the *same* snapshot, so
 * switching between overlay and island output never re-collects data.
 */
data class MetricSnapshot(
    val values: Map<String, MetricValue> = emptyMap(),
    val timestampMs: Long = 0L,
) {
    operator fun get(name: String): MetricValue? = values[name]

    /** Numeric reading for [name], or null when the metric is missing or unavailable. */
    fun numberOrNull(name: String): Double? = (values[name] as? MetricValue.Number)?.value

    /** True when [name] currently has a real reading. */
    fun isAvailable(name: String): Boolean = values[name]?.isAvailable == true

    fun text(name: String): String? = when (val value = values[name]) {
        is MetricValue.Text -> value.value
        is MetricValue.Number -> formatPlain(value.value)
        else -> null
    }

    /** Reason a metric is unavailable, used by the diagnostics page. */
    fun unavailableReason(name: String): UnavailableReason? =
        (values[name] as? MetricValue.Unavailable)?.reason

    fun merge(other: Map<String, MetricValue>): MetricSnapshot =
        MetricSnapshot(values + other, maxOf(timestampMs, System.currentTimeMillis()))

    private fun formatPlain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    companion object {
        val Empty = MetricSnapshot(emptyMap(), 0L)
    }
}
