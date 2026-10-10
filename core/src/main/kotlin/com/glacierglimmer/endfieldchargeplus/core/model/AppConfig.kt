package com.glacierglimmer.endfieldchargeplus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The persisted configuration of the Android edition.
 *
 * The first block mirrors `EndfieldChargePlus.Settings.AppSettings` from the Windows edition
 * (same names, same defaults, same meaning) so an exported desktop configuration stays meaningful
 * on Android. Everything that only exists on Android lives in [AndroidSettings].
 */
@Serializable
data class AppConfig(
    @SerialName("SchemaVersion") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    /** Master HUD switch. */
    @SerialName("HudEnabled") val hudEnabled: Boolean = true,

    /** `Auto`, `zh-CN` or `en-US`. `Auto` follows the Android system locale. */
    @SerialName("UiLanguage") val uiLanguage: String = "Auto",

    @SerialName("GlobalScale") val globalScale: Double = DEFAULT_GLOBAL_SCALE,
    @SerialName("DisplayDurationSeconds") val displayDurationSeconds: Double = DEFAULT_DISPLAY_DURATION_SECONDS,
    @SerialName("BounceStrength") val bounceStrength: Double = DEFAULT_BOUNCE_STRENGTH,
    @SerialName("RippleIntensity") val rippleIntensity: Double = DEFAULT_RIPPLE_INTENSITY,
    @SerialName("RippleSpread") val rippleSpread: Double = DEFAULT_RIPPLE_SPREAD,
    @SerialName("HudOpacity") val hudOpacity: Double = DEFAULT_HUD_OPACITY,

    @SerialName("PositionMode") val positionMode: String = "Preset",
    @SerialName("HudPosition") val hudPosition: String = "TopCenter",
    @SerialName("HudOffsetX") val hudOffsetX: Int = 0,
    @SerialName("HudOffsetY") val hudOffsetY: Int = 0,
    @SerialName("HudCustomX") val hudCustomX: Int = 0,
    @SerialName("HudCustomY") val hudCustomY: Int = 0,

    @SerialName("CustomHud") val customHud: CustomHudSettings = CustomHudSettings.createDefault(),

    @SerialName("Android") val android: AndroidSettings = AndroidSettings(),
) {
    val language: AppLanguage get() = AppLanguage.fromWire(uiLanguage)
    val positionModeEnum: HudPositionMode get() = HudPositionMode.fromWire(positionMode)
    val positionEnum: HudPosition get() = HudPosition.fromWire(hudPosition)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_GLOBAL_SCALE = 0.8
        const val DEFAULT_DISPLAY_DURATION_SECONDS = 6.0
        const val DEFAULT_BOUNCE_STRENGTH = 0.275
        const val DEFAULT_RIPPLE_INTENSITY = 1.0
        const val DEFAULT_RIPPLE_SPREAD = 1.0
        const val DEFAULT_HUD_OPACITY = 1.0
    }
}

/**
 * Settings that exist only because the product now runs on Android.
 *
 * Nothing here is invented for looks: every field controls a real Android capability described in
 * the project documentation (overlay window, foreground service, sampling cadence, island backend).
 */
@Serializable
data class AndroidSettings(
    /** `Overlay` or `Island`. */
    @SerialName("DisplayMode") val displayMode: String = "Overlay",

    /** Free overlay position, kept per orientation so rotation restores the user's placement. */
    @SerialName("OverlayXPortrait") val overlayXPortrait: Int = -1,
    @SerialName("OverlayYPortrait") val overlayYPortrait: Int = -1,
    @SerialName("OverlayXLandscape") val overlayXLandscape: Int = -1,
    @SerialName("OverlayYLandscape") val overlayYLandscape: Int = -1,
    /** A new drag explicitly overrides the selected anchor; old stored coordinates stay inactive. */
    @SerialName("UseDraggedPosition") val useDraggedPosition: Boolean = false,

    /** Extra HUD scale applied on top of [AppConfig.globalScale]. */
    @SerialName("HudScale") val hudScale: Double = 1.0,

    /** When true the overlay window never consumes touches (FLAG_NOT_TOUCHABLE). */
    @SerialName("ClickThrough") val clickThrough: Boolean = true,

    /** How long the HUD stays fully drawn after a reveal before the full animation hides it. */
    @SerialName("AutoHideSeconds") val autoHideSeconds: Double = 6.0,

    /** Keep the HUD permanently visible instead of running the hide/reveal cycle. */
    @SerialName("AlwaysVisible") val alwaysVisible: Boolean = true,

    /** Native Android live updates; legacy backend selections are migrated on load. */
    @SerialName("IslandProvider") val islandProvider: String = "AndroidSystem",

    /** Sampling cadences in milliseconds. Fast covers latency, normal covers system counters. */
    @SerialName("FastRefreshMs") val fastRefreshMs: Long = 500L,
    @SerialName("NormalRefreshMs") val normalRefreshMs: Long = 1_000L,
    @SerialName("SlowRefreshMs") val slowRefreshMs: Long = 5_000L,
    @SerialName("IdleRefreshMs") val idleRefreshMs: Long = 30_000L,

    /** Slow the sampler down while the HUD is hidden, and further while the screen is off. */
    @SerialName("ThrottleWhenHidden") val throttleWhenHidden: Boolean = true,
    @SerialName("ThrottleWhenScreenOff") val throttleWhenScreenOff: Boolean = true,
    @SerialName("ScreenOffRefreshMs") val screenOffRefreshMs: Long = 15_000L,

    /** Network packet probe. Uses ICMP when possible; TCP/UDP are used on restricted networks. */
    @SerialName("ProbeEnabled") val probeEnabled: Boolean = false,
    @SerialName("ProbeIntervalSeconds") val probeIntervalSeconds: Int = 2,
    @SerialName("ProbeTimeoutMs") val probeTimeoutMs: Int = 1_000,
    @SerialName("ProbeSampleWindow") val probeSampleWindow: Int = 12,

    /** DeepSeek balance endpoint refresh cadence. Never polled at HUD refresh speed. */
    @SerialName("DeepSeekRefreshSeconds") val deepSeekRefreshSeconds: Int = 300,
    @SerialName("DeepSeekBaseUrl") val deepSeekBaseUrl: String = "https://api.deepseek.com",

    /** Start the HUD service after boot when the user has explicitly enabled it. */
    @SerialName("StartOnBoot") val startOnBoot: Boolean = false,

    /** Avoid the display cutout / status bar area when anchoring the overlay. */
    @SerialName("AvoidCutout") val avoidCutout: Boolean = true,

    /** Verbose diagnostics logging in the in-app log viewer. */
    @SerialName("VerboseLogging") val verboseLogging: Boolean = false,

    /** Explicit opt-in to read-only kernel collection through the user's existing su manager. */
    @SerialName("UseRoot") val useRoot: Boolean = false,

    /** Remember the last hardware capability scan result timestamp (epoch millis, 0 = never). */
    @SerialName("CapabilityScanAt") val capabilityScanAt: Long = 0L,
)
