package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.NetworkDisplayUnit
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkPercentMode
import java.util.Locale
import kotlin.math.round

/**
 * Presentation of network rates, ported from `HudProfileRenderer.FormatNetworkSpeed` and
 * `ToBytesPerSecond` of the Windows edition.
 *
 * Notes on fidelity:
 *  * `AutoBytes` uses decimal SI units (`KB/s`, `MB/s`), exactly like the desktop editions.
 *  * `Mbps` uses decimal megabits (`bps * 8 / 1_000_000`).
 *  * An unknown `NetworkReferenceUnit` is treated as `MB/s`, which is ECP's documented fallback.
 */
internal object NetworkFormat {

    fun autoBytes(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1_000_000.0) {
            "${oneDecimal(bytesPerSecond / 1_000.0)} KB/s"
        } else {
            "${oneDecimal(bytesPerSecond / 1_000_000.0)} MB/s"
        }

    fun mbps(bytesPerSecond: Double): String =
        "${oneDecimal(bytesPerSecond * 8.0 / 1_000_000.0)} Mbps"

    /** Renders a rate according to the active profile's display unit. */
    fun display(bytesPerSecond: Double, unit: NetworkDisplayUnit): String = when (unit) {
        NetworkDisplayUnit.MBPS -> mbps(bytesPerSecond)
        NetworkDisplayUnit.AUTO_BYTES -> autoBytes(bytesPerSecond)
    }

    /** Converts a profile reference value to bytes per second. */
    fun referenceBytesPerSecond(value: Double, unit: String): Double? {
        if (!value.isFinite() || value < 0.0) return null
        return when (unit.trim().lowercase(Locale.US)) {
            "mbps" -> value * 1_000_000.0 / 8.0
            "kb/s", "kbps", "kbyte/s" -> value * 1_000.0
            "mb/s", "mbyte/s" -> value * 1_000_000.0
            "b/s", "byte/s", "bps" -> value
            // ECP's documented fallback for an unrecognised reference unit.
            else -> value * 1_000_000.0
        }
    }

    /**
     * The measured rate that feeds the profile percentage, or `null` when neither direction has a
     * real rate yet (in which case the percentage must not be published as 0).
     */
    fun measuredBytesPerSecond(
        mode: NetworkPercentMode,
        downloadBytesPerSecond: Double?,
        uploadBytesPerSecond: Double?,
    ): Double? = when (mode) {
        NetworkPercentMode.TOTAL -> {
            val values = listOfNotNull(downloadBytesPerSecond, uploadBytesPerSecond)
            if (values.isEmpty()) null else values.sum()
        }

        NetworkPercentMode.DOWNLOAD -> downloadBytesPerSecond
        NetworkPercentMode.UPLOAD -> uploadBytesPerSecond
        NetworkPercentMode.MAX -> listOfNotNull(downloadBytesPerSecond, uploadBytesPerSecond).maxOrNull()
    }

    /** `clamp(measured / reference * 100, 0, 100)`; a non-positive reference yields 0, as in ECP. */
    fun percent(measuredBytesPerSecond: Double, referenceBytesPerSecond: Double): Double {
        if (referenceBytesPerSecond <= 0.0 || !measuredBytesPerSecond.isFinite()) return 0.0
        return (measuredBytesPerSecond / referenceBytesPerSecond * 100.0).coerceIn(0.0, 100.0)
    }

    /** `"↓ 42%"` / `"↑ 42%"` / `"42%"`, with .NET's away-from-zero rounding. */
    fun percentText(percent: Double, mode: NetworkPercentMode): String {
        val prefix = when (mode) {
            NetworkPercentMode.DOWNLOAD -> "↓ "
            NetworkPercentMode.UPLOAD -> "↑ "
            NetworkPercentMode.TOTAL, NetworkPercentMode.MAX -> ""
        }
        return "$prefix${round(percent).toInt()}%"
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}

/**
 * Battery energy arithmetic.
 *
 * Android reports charge in µAh and voltage in mV; mWh is therefore `µAh × mV / 1e6`. Values are
 * only produced when the inputs are physically plausible, because a wrong mWh reading is worse
 * than an honest `--` in the HUD.
 */
internal object BatteryEnergyMath {

    private const val MAX_PLAUSIBLE_MWH = 300_000.0

    /** Charge counter (µAh) × voltage (mV) → mWh, or `null` when the inputs are not trustworthy. */
    fun chargeToMilliWattHours(microAmpHours: Double, milliVolts: Double): Double? {
        if (microAmpHours <= 0.0 || milliVolts <= 0.0) return null
        val energy = microAmpHours * milliVolts / 1_000_000.0
        return energy.takeIf { it in 1.0..MAX_PLAUSIBLE_MWH }
    }

    /** Extrapolates pack capacity from the remaining energy and the charge percentage. */
    fun fullMilliWattHours(remainingMilliWattHours: Double, percent: Double): Double? {
        if (percent !in 3.0..100.0 || remainingMilliWattHours <= 0.0) return null
        val full = remainingMilliWattHours * 100.0 / percent
        return full.takeIf { it >= remainingMilliWattHours && it <= MAX_PLAUSIBLE_MWH }
    }

    /**
     * Delta-based current estimate in mA (magnitude) from the charge counter, used only when
     * `BATTERY_PROPERTY_CURRENT_NOW` is unsupported.
     */
    fun currentMilliAmpsFromCounterDelta(deltaMicroAmpHours: Double, elapsedMs: Long): Double? {
        if (elapsedMs <= 0L) return null
        val milliAmps = deltaMicroAmpHours * 3_600.0 / elapsedMs.toDouble()
        return milliAmps.takeIf { it >= 0.0 && it <= 20_000.0 }
    }

    fun currentMagnitudeFromSignedCounterDelta(deltaMicroAmpHours: Double, elapsedMs: Long): Double? =
        currentMilliAmpsFromCounterDelta(kotlin.math.abs(deltaMicroAmpHours), elapsedMs)
}
