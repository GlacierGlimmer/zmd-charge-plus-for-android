package com.glacierglimmer.endfieldchargeplus.core.model

/**
 * A single metric reading produced by a collector.
 *
 * ECP's cross-platform rule is strict: a metric that the platform cannot read must never be
 * reported as a real `0`. It is reported as [Unavailable] so that the template layer can render
 * the platform sentinel instead of fabricating data.
 */
sealed interface MetricValue {

    /** True only when the platform actually produced a reading. */
    val isAvailable: Boolean

    /** A numeric reading. */
    data class Number(val value: Double) : MetricValue {
        override val isAvailable: Boolean get() = true
    }

    /** A textual reading (network type, power source, connection state, ...). */
    data class Text(val value: String) : MetricValue {
        override val isAvailable: Boolean get() = true
    }

    /** A value that could not be read, with a machine readable reason. */
    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String = "",
    ) : MetricValue {
        override val isAvailable: Boolean get() = false
    }

    companion object {
        val NotSupported: MetricValue = Unavailable(UnavailableReason.NOT_SUPPORTED)
        val PermissionRequired: MetricValue = Unavailable(UnavailableReason.PERMISSION_REQUIRED)
        val NoData: MetricValue = Unavailable(UnavailableReason.NO_DATA)
        val Disabled: MetricValue = Unavailable(UnavailableReason.DISABLED)

        fun number(value: Double?): MetricValue =
            if (value == null || value.isNaN() || value.isInfinite()) NoData else Number(value)

        fun text(value: String?): MetricValue =
            if (value.isNullOrBlank()) NoData else Text(value)
    }
}

/** Why a metric has no value. Mirrors the wording used by the settings UI diagnostics page. */
enum class UnavailableReason {
    /** The Android platform exposes no public API for this metric (for example GPU load). */
    NOT_SUPPORTED,

    /** A runtime permission or special access (for example overlay) is required first. */
    PERMISSION_REQUIRED,

    /** This particular device/kernel does not expose the node or system service. */
    NOT_AVAILABLE_ON_DEVICE,

    /** The value has not been sampled yet, or the sample window is still filling. */
    NO_DATA,

    /** The user disabled the data source. */
    DISABLED,

    /** A network request failed. */
    NETWORK_ERROR,

    /** The vendor island/vendor API needs authorization that has not been granted. */
    NOT_AUTHORIZED,
}
