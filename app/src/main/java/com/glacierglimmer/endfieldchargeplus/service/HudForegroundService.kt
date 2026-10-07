package com.glacierglimmer.endfieldchargeplus.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.hud.HudStateBuilder
import com.glacierglimmer.endfieldchargeplus.metrics.MetricDemand
import com.glacierglimmer.endfieldchargeplus.metrics.MetricRepository
import com.glacierglimmer.endfieldchargeplus.overlay.DefaultOverlayController
import com.glacierglimmer.endfieldchargeplus.overlay.HudCycleOrder
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayController
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayStateListener
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the whole HUD lifecycle and is the only place that wires the components together.
 *
 * Responsibilities (everything else is delegated):
 *  * runs as a `specialUse` foreground service with the [HudNotificationFactory] notification;
 *  * creates **one** [MetricRepository] per service run and shares it between the overlay and the
 *    island output — switching [DisplayMode] only swaps the renderer, never the data pipeline;
 *  * observes `ConfigRepository.config` and `MetricRepository.snapshot`, builds the render data with
 *    the shared [HudStateBuilder] and pushes it into the active output;
 *  * runs the auto-cycle carousel (`effectiveCycleProfileIds`, `cycleSeconds`,
 *    `cycleAnimationMode`) and re-resolves the queue on every tick, so a deleted scheme is skipped;
 *  * reacts to screen on/off and window visibility through `MetricRepository.setDemand` and
 *    `setActiveProfile`;
 *  * survives the Activity being swiped away (`android:stopWithTask="false"`, `START_STICKY`) and
 *    stops cleanly (`stopForeground` + release + `stopSelf`) when the user turns the HUD off or the
 *    overlay permission is revoked.
 *
 * A `Service` is instantiated by the framework and therefore cannot take constructor parameters, so
 * collaborators come from [EcpContainer] through the overridable [createContainer] seam.
 */
open class HudForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var container: EcpContainer
    private lateinit var configRepository: ConfigRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var stateBuilder: HudStateBuilder
    private lateinit var overlay: OverlayController

    private var islandBridge: IslandOutput? = null

    private var config: AppConfig = AppConfig()
    private var configObserved = false
    private var displayMode = DisplayMode.OVERLAY
    private var islandActive = false
    private var screenOn = true
    private var displayedProfileId = ""
    private var cycleIndex = 0
    private var cycleJob: Job? = null
    private var stopping = false

    private val overlayVisibilityListener = object : OverlayStateListener {
        override fun onOverlayVisibilityChanged(showing: Boolean) {
            applyDemand()
            publishStatus()
            if (!showing && config.hudEnabled && displayMode == DisplayMode.OVERLAY && !overlay.canShow()) {
                AppLog.w(TAG, "Overlay permission was revoked while the HUD was running")
                stopHud()
            }
        }
    }

    /** Turns screen state into sampling demand; this is the only power-related policy here. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
            applyDemand()
        }
    }

    /** Overridable test seam: the process container that owns every collaborator. */
    protected open fun createContainer(): EcpContainer = EcpContainer.of(applicationContext)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        configRepository = container.configRepository
        metricRepository = container.metricRepository
        stateBuilder = container.hudStateBuilder
        overlay = container.overlayController
        (overlay as? DefaultOverlayController)?.setStateListener(overlayVisibilityListener)

        config = configRepository.config.value
        displayMode = DisplayMode.fromWire(config.android.displayMode)
        startAsForegroundService()
        registerScreenReceiver()
        observeConfig()
        observeSnapshot()
        metricRepository.start()
        HudRuntimeState.update {
            it.copy(
                serviceRunning = true,
                displayMode = displayMode,
                overlayShowing = false,
                activeProfileId = displayedProfileId,
                repositoryRunning = metricRepository.running.value,
                lastError = null,
            )
        }
        AppLog.i(TAG, "HUD service started (mode=${displayMode.wire})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HudServiceController.ACTION_STOP) {
            stopHud()
            return START_NOT_STICKY
        }
        if (configObserved && !config.hudEnabled) {
            // The master switch is off: a restart (or a stale intent) must not resurrect the HUD.
            stopHud()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Rotation, resolution or density change: the overlay re-resolves its anchor (the per-orientation
     * free position) and the sampling demand is refreshed.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.onConfigurationChanged()
        applyDemand()
        publishStatus()
    }

    override fun onDestroy() {
        cycleJob?.cancel()
        cycleJob = null
        runCatching { unregisterReceiver(screenReceiver) }
        (overlay as? DefaultOverlayController)?.setStateListener(null)
        releaseOutputs()
        serviceScope.cancel()
        HudRuntimeState.publish(
            HudRuntimeStatus(
                serviceRunning = false,
                displayMode = displayMode,
                overlayShowing = false,
                activeProfileId = displayedProfileId,
            ),
        )
        AppLog.i(TAG, "HUD service destroyed")
        super.onDestroy()
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    private fun startAsForegroundService() {
        HudNotificationFactory.ensureChannel(this, uiLanguage())
        val notification = HudNotificationFactory.build(this, uiLanguage())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the declared type at start time; the manifest declares specialUse.
            startForeground(
                HudNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(HudNotificationFactory.NOTIFICATION_ID, notification)
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun stopHud() {
        if (stopping) return
        stopping = true
        AppLog.i(TAG, "Stopping the HUD service")
        cycleJob?.cancel()
        cycleJob = null
        releaseOutputs()
        HudRuntimeState.publish(
            HudRuntimeStatus(
                serviceRunning = false,
                displayMode = displayMode,
                overlayShowing = false,
                activeProfileId = displayedProfileId,
            ),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseOutputs() {
        runCatching { overlay.release() }.onFailure { AppLog.w(TAG, "Overlay release failed", it) }
        runCatching { islandBridge?.stop() }.onFailure { AppLog.w(TAG, "Island stop failed", it) }
        runCatching { metricRepository.stop() }.onFailure { AppLog.w(TAG, "Repository stop failed", it) }
        islandActive = false
    }

    // ---- data pipeline -----------------------------------------------------------------------

    private fun observeConfig() {
        serviceScope.launch {
            configRepository.config.collect { newConfig -> onConfigChanged(newConfig) }
        }
    }

    private fun observeSnapshot() {
        serviceScope.launch {
            metricRepository.snapshot.collect { render(animate = false) }
        }
    }

    private fun onConfigChanged(newConfig: AppConfig) {
        val previous = config
        config = newConfig
        configObserved = true
        AppLog.setVerbose(newConfig.android.verboseLogging)

        if (!newConfig.hudEnabled) {
            stopHud()
            return
        }

        val mode = DisplayMode.fromWire(newConfig.android.displayMode)
        val modeChanged = mode != displayMode
        val profileChanged = previous.customHud.activeProfileId != newConfig.customHud.activeProfileId ||
            previous.customHud.autoCycle != newConfig.customHud.autoCycle
        displayMode = mode

        overlay.applyConfig(newConfig)
        applyDisplayMode(mode)
        restartCycleTimer(newConfig)
        applyDemand()
        render(animate = modeChanged || profileChanged || displayedProfileId.isEmpty())
        publishStatus()
    }

    /** Builds and pushes the render data for the currently displayed scheme. */
    private fun render(animate: Boolean) {
        val profile = renderedProfile() ?: return
        push(profile, animate)
    }

    private fun push(profile: HudProfile, animate: Boolean) {
        val data = stateBuilder.build(profile, metricRepository.snapshot.value, uiLanguage())
        displayedProfileId = profile.id
        metricRepository.setActiveProfile(profile)
        if (displayMode == DisplayMode.ISLAND) {
            islandBridge?.update(data)
        } else if (overlay.isShowing() || animate) {
            // A hidden transient HUD is only re-summoned by an explicit content change.
            overlay.update(data, animate)
        }
        publishStatus()
    }

    /** The scheme that must be visible right now: the carousel entry, or the configured one. */
    private fun renderedProfile(): HudProfile? {
        if (!config.customHud.autoCycle) return HudCycleOrder.activeProfile(config)
        cycleIndex = HudCycleOrder.clampIndex(config, cycleIndex)
        return HudCycleOrder.resolve(config).getOrNull(cycleIndex)
            ?: HudCycleOrder.activeProfile(config)
    }

    private fun uiLanguage(): UiLanguage =
        UiLanguage.fromAppLanguage(config.language, com.glacierglimmer.endfieldchargeplus.localization.LanguageController.systemLanguageTag())

    // ---- outputs -----------------------------------------------------------------------------

    private fun applyDisplayMode(mode: DisplayMode) {
        if (mode == DisplayMode.OVERLAY) {
            if (islandActive) {
                islandBridge?.stop()
                islandActive = false
            }
            if (!overlay.canShow()) {
                if (overlay.isShowing()) overlay.hide()
                HudRuntimeState.update {
                    it.copy(
                        overlayShowing = false,
                        lastError = DefaultOverlayController.ERROR_OVERLAY_PERMISSION,
                    )
                }
            }
            // The overlay itself is attached by the next render (animate = true when the mode or the
            // scheme changed), so the window is never created without content.
        } else {
            if (overlay.isShowing()) overlay.hide()
            val output = islandOutput()
            // Only claim the island path is active when a provider really started; otherwise the
            // runtime status must keep reporting the honest availability instead of a false success.
            islandActive = output.start()
            if (!islandActive) {
                HudRuntimeState.update {
                    it.copy(
                        islandProviderId = null,
                        islandAvailability = output.availability(),
                    )
                }
            }
        }
    }

    /** The island output, built once per service run with the service's real notification host. */
    private fun islandOutput(): IslandOutput = islandBridge ?: RegistryIslandOutput(
        registry = container.islandRegistry.withNotificationHost(ServiceIslandNotificationHost(this)),
        scope = serviceScope,
    ).also { islandBridge = it }

    private fun applyDemand() {
        metricRepository.setDemand(
            MetricDemand(
                // The service owns an active output while it runs, so sampling stays alive even
                // between two transient reveals.
                outputActive = config.hudEnabled,
                hudVisible = overlay.isShowing() || islandActive,
                foregroundUi = false,
                screenOn = screenOn,
                userPaused = !config.hudEnabled,
            ),
        )
    }

    private fun restartCycleTimer(newConfig: AppConfig) {
        cycleJob?.cancel()
        cycleJob = null
        if (!newConfig.customHud.autoCycle) {
            cycleIndex = 0
            return
        }
        cycleIndex = HudCycleOrder.clampIndex(newConfig, cycleIndex)
        if (HudCycleOrder.resolve(newConfig).size <= 1) return
        val seconds = newConfig.customHud.cycleSeconds.coerceIn(MIN_CYCLE_SECONDS, MAX_CYCLE_SECONDS)
        cycleJob = serviceScope.launch {
            while (isActive) {
                delay(seconds * 1000L)
                advanceCycle()
            }
        }
    }

    private fun advanceCycle() {
        if (HudCycleOrder.resolve(config).isEmpty()) return
        cycleIndex = HudCycleOrder.next(config, cycleIndex)
        // A cycle change is always "隐去 → 内容切换 → 唤出", never an in-place morph.
        render(animate = true)
    }

    private fun publishStatus() {
        HudRuntimeState.update {
            it.copy(
                serviceRunning = !stopping,
                displayMode = displayMode,
                overlayShowing = overlay.isShowing(),
                islandProviderId = islandBridge?.providerId(),
                islandAvailability = islandBridge?.availability(),
                activeProfileId = displayedProfileId,
                repositoryRunning = metricRepository.running.value,
            )
        }
    }

    private companion object {
        const val TAG = "HudForegroundService"

        /** Desktop clamp of `CycleSeconds` (`HudSettingsNormalizer`). */
        const val MIN_CYCLE_SECONDS = 3
        const val MAX_CYCLE_SECONDS = 3600
    }
}
