package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * Device identity and screen state.
 *
 * `device.brightness` is the raw `Settings.System.SCREEN_BRIGHTNESS` value Android stores (0..255);
 * it is only published when the setting really is readable (a restricted device or a managed
 * profile can deny it) and never defaulted.
 */
class DeviceCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "device"

    override val tier: SamplingTier = SamplingTier.SLOW

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        into.putText(Variables.DEVICE_MODEL, Build.MODEL)
        into.putText(Variables.DEVICE_MANUFACTURER, Build.MANUFACTURER)
        into.putText(Variables.DEVICE_ANDROID_VERSION, Build.VERSION.RELEASE)
        into[Variables.DEVICE_SDK] = MetricValue.Number(Build.VERSION.SDK_INT.toDouble())
        into[Variables.DEVICE_UPTIME_SECONDS] = MetricValue.Number(SystemClock.elapsedRealtime() / 1000.0)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            into.putUnavailable(
                Variables.DEVICE_SCREEN_STATE,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "PowerManager unavailable",
            )
        } else {
            into[Variables.DEVICE_SCREEN_STATE] = MetricValue.Text(if (powerManager.isInteractive) "on" else "off")
        }

        val (brightness, brightnessReason) = readBrightness()
        if (brightness == null) {
            into.putUnavailable(
                Variables.DEVICE_BRIGHTNESS,
                brightnessReason,
                "Settings.System.SCREEN_BRIGHTNESS is not readable for this app",
            )
        } else {
            into[Variables.DEVICE_BRIGHTNESS] = MetricValue.Number(brightness.toDouble())
        }
    }

    /** Raw brightness value Android stores (0..255) plus the honest reason when it is unreadable. */
    private fun readBrightness(): Pair<Int?, UnavailableReason> = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) to
            UnavailableReason.NO_DATA
    } catch (error: SecurityException) {
        null to UnavailableReason.PERMISSION_REQUIRED
    } catch (error: Settings.SettingNotFoundException) {
        null to UnavailableReason.NOT_AVAILABLE_ON_DEVICE
    }
}
