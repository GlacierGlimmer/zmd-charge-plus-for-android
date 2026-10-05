package com.glacierglimmer.endfieldchargeplus.diagnostics

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import com.glacierglimmer.endfieldchargeplus.permission.PermissionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text, shareable diagnostics report.
 *
 * Everything the support workflow needs is here: app/device identity, screen and display state, the
 * active scheme, sampling cadence, probe/DeepSeek configuration, the permission table, the hardware
 * capability table and the tail of the in-app log.
 *
 * The DeepSeek key is **never** printed. Only a masked form of the protected blob is shown, and the
 * whole report is passed through [AppLog.redact] as a final safety net.
 */
object DiagnosticsReport {

    private const val LOG_TAIL_ENTRIES = 120

    fun build(
        context: Context,
        config: AppConfig,
        capabilities: HardwareCapabilities?,
        permissions: List<PermissionState>,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val report = StringBuilder()
        report.appendLine("Endfield Charge Plus for Android — diagnostics report")
        report.appendLine("generated=${timestamp()}")
        report.appendLine()

        appendApp(context, report)
        appendDevice(context, report)
        appendConfiguration(config, report)
        appendPermissions(permissions, report)
        appendCapabilities(capabilities, report)
        appendExtra(extra, report)
        appendLogTail(report)

        // Final safety net: any registered secret is masked even if it slipped into a line above.
        return AppLog.redact(report.toString())
    }

    private fun appendApp(context: Context, report: StringBuilder) {
        report.appendLine("--- App ---")
        report.appendLine("package: ${context.packageName}")
        report.appendLine("version: ${appVersion(context)}")
        report.appendLine()
    }

    private fun appendDevice(context: Context, report: StringBuilder) {
        report.appendLine("--- Device ---")
        report.appendLine("manufacturer: ${Build.MANUFACTURER}")
        report.appendLine("model: ${Build.MODEL}")
        report.appendLine("device: ${Build.DEVICE}")
        report.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        report.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        runCatching {
            val metrics = context.resources.displayMetrics
            val configuration = context.resources.configuration
            report.appendLine(
                "screen: ${metrics.widthPixels}x${metrics.heightPixels} px, " +
                    "${metrics.densityDpi} dpi (density ${metrics.density})",
            )
            report.appendLine(
                "screen class: ${orientationName(configuration.orientation)}, " +
                    "smallestWidth ${configuration.smallestScreenWidthDp} dp",
            )
        }.onFailure { report.appendLine("screen: unavailable (${it.javaClass.simpleName})") }
        report.appendLine()
    }

    private fun appendConfiguration(config: AppConfig, report: StringBuilder) {
        val android = config.android
        report.appendLine("--- Configuration ---")
        report.appendLine("schemaVersion: ${config.schemaVersion}")
        report.appendLine("hudEnabled: ${config.hudEnabled}")
        report.appendLine("uiLanguage: ${config.uiLanguage}")
        report.appendLine("displayMode: ${android.displayMode}")
        report.appendLine("islandProvider: ${android.islandProvider}")
        report.appendLine("activeProfile: ${config.customHud.activeProfile()?.name ?: "(none)"} " +
            "[${config.customHud.activeProfileId}]")
        report.appendLine("profiles: ${config.customHud.profiles.size} " +
            "(carousel queue ${config.customHud.effectiveCycleProfileIds().size})")
        report.appendLine("autoCycle: ${config.customHud.autoCycle}, cycleSeconds: ${config.customHud.cycleSeconds}, " +
            "cycleAnimationMode: ${config.customHud.cycleAnimationMode}")
        report.appendLine("scale: global=${config.globalScale}, hud=${android.hudScale}, opacity=${config.hudOpacity}")
        report.appendLine(
            "sampling: fast=${android.fastRefreshMs}ms normal=${android.normalRefreshMs}ms " +
                "slow=${android.slowRefreshMs}ms idle=${android.idleRefreshMs}ms " +
                "screenOff=${android.screenOffRefreshMs}ms",
        )
        report.appendLine(
            "throttle: hidden=${android.throttleWhenHidden}, screenOff=${android.throttleWhenScreenOff}",
        )
        report.appendLine("alwaysVisible: ${android.alwaysVisible}, clickThrough: ${android.clickThrough}, " +
            "autoHideSeconds: ${android.autoHideSeconds}")
        report.appendLine("probe: enabled=${android.probeEnabled}, interval=${android.probeIntervalSeconds}s, " +
            "timeout=${android.probeTimeoutMs}ms, window=${android.probeSampleWindow}")
        report.appendLine("probe protocols: icmp/tcp/udp rendered by the probe collector")
        report.appendLine("deepSeek: baseUrl=${android.deepSeekBaseUrl}, refresh=${android.deepSeekRefreshSeconds}s")
        report.appendLine("deepSeek key: ${maskedKey(config.customHud.deepSeekApiKeyProtected)}")
        report.appendLine("deepSeek peak windows: ${config.customHud.deepSeekPeakWindows}")
        report.appendLine("startOnBoot: ${android.startOnBoot}, avoidCutout: ${android.avoidCutout}, " +
            "verboseLogging: ${android.verboseLogging}")
        report.appendLine("capabilityScanAt: ${android.capabilityScanAt}")
        report.appendLine()
    }

    private fun appendPermissions(permissions: List<PermissionState>, report: StringBuilder) {
        report.appendLine("--- Permissions ---")
        if (permissions.isEmpty()) {
            report.appendLine("(not collected)")
        } else {
            permissions.forEach { state ->
                report.appendLine(
                    "${state.permission.name.lowercase(Locale.US)}: " +
                        "${if (state.granted) "granted" else "not granted"} " +
                        "[${state.messageKey}]${if (state.detail.isBlank()) "" else " ${state.detail}"}",
                )
            }
        }
        report.appendLine()
    }

    private fun appendCapabilities(capabilities: HardwareCapabilities?, report: StringBuilder) {
        report.appendLine("--- Hardware capabilities ---")
        if (capabilities == null) {
            report.appendLine("(not scanned)")
            report.appendLine()
            return
        }
        report.appendLine("cpuModel: ${capabilities.cpuModel.ifBlank { "unknown" }}")
        report.appendLine("cpuCoreCount: ${capabilities.cpuCoreCount}")
        report.appendLine("deviceModel: ${capabilities.deviceModel.ifBlank { "unknown" }}")
        report.appendLine("androidRelease: ${capabilities.androidRelease.ifBlank { "unknown" }}")
        report.appendLine("sdkInt: ${capabilities.sdkInt}")
        report.appendLine("scannedAt: ${capabilities.scannedAtMs}")
        capabilities.summarise().forEach { (label, capability) ->
            val verdict = if (capability.supported) "supported" else "unsupported"
            val reason = capability.reasonKey.ifBlank { "-" }
            val detail = capability.detail
            report.appendLine("$label: $verdict [$reason]${if (detail.isBlank()) "" else " $detail"}")
        }
        report.appendLine()
    }

    private fun appendExtra(extra: Map<String, String>, report: StringBuilder) {
        if (extra.isEmpty()) return
        report.appendLine("--- Extra ---")
        extra.toSortedMap().forEach { (key, value) -> report.appendLine("$key: ${AppLog.redact(value)}") }
        report.appendLine()
    }

    private fun appendLogTail(report: StringBuilder) {
        report.appendLine("--- Log tail (last $LOG_TAIL_ENTRIES entries) ---")
        val entries = AppLog.entries().takeLast(LOG_TAIL_ENTRIES)
        if (entries.isEmpty()) {
            report.appendLine("(empty)")
        } else {
            entries.forEach { report.appendLine(it.format()) }
        }
    }

    private fun maskedKey(protectedValue: String): String =
        if (protectedValue.isBlank()) "(not set)" else "set, protected=${AppLog.mask(protectedValue.trim())}"

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName ?: "unknown"} ($code)"
    }.getOrElse { "unknown" }

    private fun orientationName(orientation: Int): String = when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
