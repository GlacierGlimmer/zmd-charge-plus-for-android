package com.glacierglimmer.endfieldchargeplus.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.overlay.DefaultOverlayController

/**
 * The only entry point the UI uses to control the HUD service.
 *
 * Everything is safe to call at any time:
 *  * [start] refuses to start the overlay output when `Settings.canDrawOverlays` is false and
 *    publishes the reason into [HudRuntimeState] instead of crashing (Android 12+ can also refuse
 *    `startForegroundService` from the background, which is reported the same way);
 *  * [stop] delivers the stop action so the service can tear the window down in order, and falls
 *    back to `stopService` when the platform will not let the app start a service right now;
 *  * [isRunning] reads [HudRuntimeState], which the service itself maintains — the service always
 *    runs in the app's own process, so this is authoritative.
 */
object HudServiceController {

    /** Action that starts the HUD service. */
    const val ACTION_START = "com.glacierglimmer.endfieldchargeplus.action.START_HUD"

    /** Action that stops the HUD service and removes the window. */
    const val ACTION_STOP = "com.glacierglimmer.endfieldchargeplus.action.STOP_HUD"

    private const val RESTART_DELAY_MS = 300L
    private const val TAG = "HudServiceController"

    /** Starts the foreground HUD service when the selected output is allowed to run. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        val config = EcpContainer.of(appContext).configRepository.config.value
        val mode = DisplayMode.fromWire(config.android.displayMode)
        if (mode == DisplayMode.OVERLAY && !Settings.canDrawOverlays(appContext)) {
            AppLog.w(TAG, "HUD start refused: overlay permission is missing")
            HudRuntimeState.update {
                it.copy(
                    serviceRunning = false,
                    displayMode = DisplayMode.OVERLAY,
                    overlayShowing = false,
                    lastError = DefaultOverlayController.ERROR_OVERLAY_PERMISSION,
                )
            }
            return
        }
        val intent = Intent(appContext, HudForegroundService::class.java).setAction(ACTION_START)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "The system refused to start the HUD service", throwable)
            HudRuntimeState.update {
                it.copy(
                    serviceRunning = false,
                    lastError = throwable.message ?: "The system refused to start the HUD service",
                )
            }
        }
    }

    /** Asks the HUD service to stop, detach the window and stop itself. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, HudForegroundService::class.java)
        val stopIntent = Intent(appContext, HudForegroundService::class.java).setAction(ACTION_STOP)
        try {
            appContext.startService(stopIntent)
        } catch (throwable: Throwable) {
            AppLog.w(TAG, "Could not deliver the stop action; stopping the service directly", throwable)
            runCatching { appContext.stopService(serviceIntent) }
                .onFailure { AppLog.e(TAG, "stopService failed", it) }
            HudRuntimeState.update { it.copy(serviceRunning = false, overlayShowing = false) }
        }
    }

    /**
     * Stops and starts again, for example after the user switched the display mode.
     *
     * The delay lets the old service finish `onDestroy` (the platform reuses the same
     * `Service` instance otherwise), so the new configuration is applied from a clean state.
     */
    fun restart(context: Context) {
        val appContext = context.applicationContext
        stop(appContext)
        Handler(Looper.getMainLooper()).postDelayed({ start(appContext) }, RESTART_DELAY_MS)
    }

    /**
     * True while the HUD service is alive.
     *
     * The service always runs in the application's own process, so the status it publishes in
     * [HudRuntimeState] is authoritative; the context parameter documents which application is
     * being asked and keeps the call site explicit.
     */
    fun isRunning(context: Context): Boolean = HudRuntimeState.status.value.serviceRunning
}
