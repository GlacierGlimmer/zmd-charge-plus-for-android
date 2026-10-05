package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the surrounding configuration that collectors may need (the active scheme's
 * probe target, HTTP source list, refresh cadence, ...). Injected into collectors so they never
 * reach into the UI or the storage layer themselves.
 */
interface MetricEnvironment {
    fun config(): AppConfig
    fun activeProfile(): HudProfile?
}

/**
 * One data source of the metric pipeline.
 *
 * Collectors never run their own timer: [SamplingScheduler] calls them according to their
 * [tier] and the current demand, which is what keeps the Android edition power friendly.
 */
interface MetricCollector {

    /** Stable identifier used by the diagnostics page. */
    val id: String

    /** Sampling cadence class. */
    val tier: SamplingTier

    /**
     * Collects the variables this collector owns and writes them into [into].
     * A collector must never write a fabricated value: unknown metrics are written as
     * [MetricValue.Unavailable] or simply omitted.
     */
    suspend fun collect(into: MutableMap<String, MetricValue>)
}

/** What the HUD currently needs, so the sampler can throttle itself. */
data class MetricDemand(
    /** Any output (overlay or island) is currently rendering. */
    val outputActive: Boolean,
    /** The HUD is visible to the user right now. */
    val hudVisible: Boolean,
    /** The settings UI is in the foreground and needs live preview values. */
    val foregroundUi: Boolean,
    /** The screen is interactive. */
    val screenOn: Boolean,
    /** Slow everything down as much as possible. */
    val userPaused: Boolean = false,
) {
    companion object {
        val Idle = MetricDemand(
            outputActive = false,
            hudVisible = false,
            foregroundUi = false,
            screenOn = true,
        )
    }
}

/**
 * The single source of metric data for every output.
 *
 * The overlay renderer and the island providers subscribe to the *same* [snapshot] flow; switching
 * display mode therefore never restarts data collection.
 */
interface MetricRepository {

    val snapshot: StateFlow<MetricSnapshot>

    /** Live hardware capability report (what Android actually lets this device/API read). */
    val capabilities: StateFlow<HardwareCapabilities>

    val running: StateFlow<Boolean>

    fun start()

    fun stop()

    /** Forces one collection pass, for example right after a config change. */
    fun refreshNow()

    fun setDemand(demand: MetricDemand)

    /** The schemes decide probe targets and network presentation; the repository forwards them. */
    fun setActiveProfile(profile: HudProfile?)

    /** Re-runs hardware capability detection (Advanced → Re-detect hardware capabilities). */
    suspend fun refreshCapabilities(): HardwareCapabilities
}
