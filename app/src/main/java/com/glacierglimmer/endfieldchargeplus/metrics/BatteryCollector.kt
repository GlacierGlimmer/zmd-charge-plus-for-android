package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * Battery state.
 *
 * The sticky `ACTION_BATTERY_CHANGED` broadcast supplies percent, temperature, voltage, status,
 * health and the plugged source; `BatteryManager` properties supply current, charge counter and
 * (when the device supports it) energy. Android's own documented caveat is honoured: a property
 * that reports `Integer.MIN_VALUE`/`Long.MIN_VALUE` is *unsupported*, so the variable becomes
 * `Unavailable`, never `0`.
 *
 * Current follows the ECP/Linux precedent rather than the sign: the magnitude is published in mA
 * and the charge/discharge direction comes from `EXTRA_STATUS`. When the property is missing, a
 * delta-based estimate from `CHARGE_COUNTER` is used, and if that is impossible too the variable
 * stays unavailable.
 */
class BatteryCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "battery"

    override val tier: SamplingTier = SamplingTier.NORMAL

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val registrationLock = Any()

    @Volatile
    private var latestIntent: Intent? = null

    private var registered = false

    private var previousChargeCounterMicroAmpHours: Long? = null

    private var previousChargeCounterAtMs: Long = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            intent?.let { latestIntent = it }
        }
    }

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val intent = latestBatteryIntent()
        if (intent == null) {
            val detail = "ACTION_BATTERY_CHANGED sticky broadcast unavailable on this device"
            for (name in ALL_VARIABLES) {
                into.putUnavailable(name, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            }
            return
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> plugged != 0
        }

        publishPercent(into, intent)
        publishTemperature(into, intent)
        publishVoltage(into, intent)
        publishCurrent(into, charging)
        publishEnergy(into, intent)
        publishEnumerations(into, status, plugged, intent)
        publishChargeCounter(into)

        into[Variables.BATTERY_CHARGING] = MetricValue.Number(if (charging) 1.0 else 0.0)
        into[Variables.BATTERY_PLUGGED] = MetricValue.Number(if (plugged != 0) 1.0 else 0.0)
    }

    private fun publishPercent(into: MutableMap<String, MetricValue>, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            into[Variables.BATTERY_PERCENT] =
                MetricValue.Number((level * 100.0 / scale.toDouble()).coerceIn(0.0, 100.0))
            return
        }
        val capacity = batteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity != null && capacity in 0L..100L) {
            into[Variables.BATTERY_PERCENT] = MetricValue.Number(capacity.toDouble())
        } else {
            into.putUnavailable(
                Variables.BATTERY_PERCENT,
                UnavailableReason.NO_DATA,
                "EXTRA_LEVEL/EXTRA_SCALE and BATTERY_PROPERTY_CAPACITY are both unusable",
            )
        }
    }

    private fun publishTemperature(into: MutableMap<String, MetricValue>, intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // The extra is tenths of a degree Celsius.
        val celsius = if (raw == Int.MIN_VALUE) null else raw / 10.0
        if (celsius != null && celsius in -30.0..100.0) {
            into[Variables.BATTERY_TEMPERATURE_C] = MetricValue.Number(celsius)
        } else {
            into.putUnavailable(
                Variables.BATTERY_TEMPERATURE_C,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "EXTRA_TEMPERATURE is absent or implausible",
            )
        }
    }

    private fun publishVoltage(into: MutableMap<String, MetricValue>, intent: Intent) {
        val milliVolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (milliVolts in 1..30_000) {
            into[Variables.BATTERY_VOLTAGE_MV] = MetricValue.Number(milliVolts.toDouble())
        } else {
            into.putUnavailable(
                Variables.BATTERY_VOLTAGE_MV,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "EXTRA_VOLTAGE is absent or implausible",
            )
        }
    }

    private fun publishCurrent(into: MutableMap<String, MetricValue>, charging: Boolean) {
        val microAmps = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (microAmps != null) {
            val milliAmps = kotlin.math.abs(microAmps) / 1000.0
            if (milliAmps <= MAX_PLAUSIBLE_CURRENT_MA) {
                into[Variables.BATTERY_CURRENT_MA] = MetricValue.Number(milliAmps)
                return
            }
        }
        val estimate = counterDeltaCurrent()
        if (estimate != null) {
            into[Variables.BATTERY_CURRENT_MA] = MetricValue.Number(estimate)
            return
        }
        into.putUnavailable(
            Variables.BATTERY_CURRENT_MA,
            UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
            "BATTERY_PROPERTY_CURRENT_NOW unsupported and CHARGE_COUNTER delta unavailable " +
                "(direction from EXTRA_STATUS, charging=$charging)",
        )
    }

    private fun counterDeltaCurrent(): Double? {
        val counter = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: return null
        val nowMs = SystemClock.elapsedRealtime()
        val previous = previousChargeCounterMicroAmpHours
        val previousAt = previousChargeCounterAtMs
        previousChargeCounterMicroAmpHours = counter
        previousChargeCounterAtMs = nowMs
        if (previous == null || previousAt <= 0L) return null
        val delta = counter - previous
        if (delta < 0L) return null
        return BatteryEnergyMath.currentMilliAmpsFromCounterDelta(delta.toDouble(), nowMs - previousAt)
    }

    private fun publishEnergy(into: MutableMap<String, MetricValue>, intent: Intent) {
        val percent = (into[Variables.BATTERY_PERCENT] as? MetricValue.Number)?.value
        val voltageMilliVolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).toDouble()

        val energyCounterNanoWattHours = batteryProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val remainingMilliWattHours: Double? = when {
            energyCounterNanoWattHours != null && energyCounterNanoWattHours > 0L ->
                (energyCounterNanoWattHours / 1_000_000.0).takeIf { it > 0.0 }

            else -> {
                val counter = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                if (counter == null) null
                else BatteryEnergyMath.chargeToMilliWattHours(counter.toDouble(), voltageMilliVolts)
            }
        }

        if (remainingMilliWattHours == null || percent == null) {
            val detail = "no trustworthy mWh input: ENERGY_COUNTER/" +
                "CHARGE_COUNTER x EXTRA_VOLTAGE or percent unavailable"
            into.putUnavailable(Variables.BATTERY_REMAINING_MWH, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable(Variables.BATTERY_FULL_MWH, UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            return
        }
        into[Variables.BATTERY_REMAINING_MWH] = MetricValue.Number(remainingMilliWattHours)
        val full = BatteryEnergyMath.fullMilliWattHours(remainingMilliWattHours, percent)
        if (full == null) {
            into.putUnavailable(
                Variables.BATTERY_FULL_MWH,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "pack capacity could not be derived safely (percent=$percent)",
            )
        } else {
            into[Variables.BATTERY_FULL_MWH] = MetricValue.Number(full)
        }
    }

    private fun publishEnumerations(
        into: MutableMap<String, MetricValue>,
        status: Int,
        plugged: Int,
        intent: Intent,
    ) {
        val statusName = BatteryText.statusName(status)
        if (statusName == null) {
            into.putUnavailable(
                Variables.BATTERY_STATUS,
                UnavailableReason.NO_DATA,
                "EXTRA_STATUS is BATTERY_STATUS_UNKNOWN",
            )
        } else {
            into[Variables.BATTERY_STATUS] = MetricValue.Text(statusName)
        }

        val healthName = BatteryText.healthName(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, Int.MIN_VALUE))
        if (healthName == null) {
            into.putUnavailable(
                Variables.BATTERY_HEALTH,
                UnavailableReason.NO_DATA,
                "EXTRA_HEALTH is absent or unknown",
            )
        } else {
            into[Variables.BATTERY_HEALTH] = MetricValue.Text(healthName)
        }

        into[Variables.BATTERY_POWER_SOURCE] = MetricValue.Text(BatteryText.powerSourceName(plugged))
    }

    private fun publishChargeCounter(into: MutableMap<String, MetricValue>) {
        val counter = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (counter == null) {
            into.putUnavailable(
                Variables.BATTERY_CHARGE_COUNTER,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "BATTERY_PROPERTY_CHARGE_COUNTER unsupported on this device",
            )
        } else {
            into[Variables.BATTERY_CHARGE_COUNTER] = MetricValue.Number(counter.toDouble())
        }
    }

    /** `BatteryManager` properties signal "unsupported" with the type minimum value. */
    private fun batteryProperty(property: Int): Long? {
        val manager = batteryManager ?: return null
        return try {
            val value = manager.getLongProperty(property)
            value.takeIf { it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Returns the freshest battery intent: a live sticky registration once, plus the sticky
     * broadcast itself. Registration is intentionally process scoped (the repository lives in the
     * foreground service) and cannot leak an Activity.
     */
    private fun latestBatteryIntent(): Intent? {
        if (!registered) {
            synchronized(registrationLock) {
                if (!registered) {
                    runCatching {
                        val sticky = registerReceiver(IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        if (sticky != null) latestIntent = sticky
                    }
                    registered = true
                }
            }
        }
        if (latestIntent == null) {
            runCatching {
                val sticky = registerReceiver(IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) latestIntent = sticky
            }
        }
        return latestIntent
    }

    private fun registerReceiver(filter: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

    private companion object {
        const val MAX_PLAUSIBLE_CURRENT_MA = 20_000.0

        val ALL_VARIABLES = listOf(
            Variables.BATTERY_PERCENT,
            Variables.BATTERY_TEMPERATURE_C,
            Variables.BATTERY_VOLTAGE_MV,
            Variables.BATTERY_CURRENT_MA,
            Variables.BATTERY_REMAINING_MWH,
            Variables.BATTERY_FULL_MWH,
            Variables.BATTERY_CHARGING,
            Variables.BATTERY_PLUGGED,
            Variables.BATTERY_POWER_SOURCE,
            Variables.BATTERY_STATUS,
            Variables.BATTERY_HEALTH,
            Variables.BATTERY_CHARGE_COUNTER,
        )
    }
}

/** Text renderings of the `ACTION_BATTERY_CHANGED` enumerations. */
internal object BatteryText {

    /** `BATTERY_PLUGGED_DOCK` is not exposed as a public constant on Android; the extra uses 8. */
    private const val PLUGGED_DOCK = 8

    fun statusName(status: Int): String? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NotCharging"
        else -> null
    }

    fun healthName(health: Int): String? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OverVoltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> null
    }

    fun powerSourceName(plugged: Int): String = when (plugged) {
        0 -> "Battery"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        PLUGGED_DOCK -> "Dock"
        else -> "Battery"
    }
}
