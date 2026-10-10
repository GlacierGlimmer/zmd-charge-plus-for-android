package com.glacierglimmer.endfieldchargeplus.ui.screens.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.permission.EcpPermission
import com.glacierglimmer.endfieldchargeplus.permission.PermissionState
import com.glacierglimmer.endfieldchargeplus.permission.PermissionStatusMonitor
import com.glacierglimmer.endfieldchargeplus.service.HudRuntimeState
import com.glacierglimmer.endfieldchargeplus.service.HudRuntimeStatus
import com.glacierglimmer.endfieldchargeplus.service.HudServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Feedback the home page shows after an action that talks to the platform. */
enum class HomeMessage {
    HUD_STARTED,
    HUD_STOPPED,
    HUD_START_FAILED,
    NOTIFICATION_PERMISSION_REQUESTED,
    OVERLAY_PERMISSION_REQUIRED,
}

/**
 * Home page state: the persisted configuration, the live HUD runtime status and the current
 * permission verdicts.
 *
 * The ViewModel never stores a copy of the configuration: it exposes the repository flow and writes
 * changes back through `configRepository.update`, so Compose state is never serialized.
 */
class HomeViewModel(private val container: EcpContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config

    val runtime: StateFlow<HudRuntimeStatus> = HudRuntimeState.status

    private val permissionMonitor = PermissionStatusMonitor(viewModelScope,
        refreshEligibility = { container.configRepository.load(); container.islandRegistry.refreshAll() },
        readPermissions = { container.permissionManager.all() })
    val permissions: StateFlow<List<PermissionState>> = permissionMonitor.permissions
    val permissionsLoading: StateFlow<Boolean> = permissionMonitor.loading
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            container.islandRegistry.statuses.collect { rows ->
                if (rows.isNotEmpty()) permissionMonitor.readCurrent()
            }
        }
    }

    private val _message = MutableStateFlow<HomeMessage?>(null)
    val message: StateFlow<HomeMessage?> = _message.asStateFlow()

    private val _pendingIntent = MutableStateFlow<Intent?>(null)
    val pendingIntent: StateFlow<Intent?> = _pendingIntent.asStateFlow()

    /** Re-reads every permission verdict; call on resume, because the user may return from settings. */
    fun refreshPermissions() {
        permissionMonitor.refresh()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeIntent() {
        _pendingIntent.value = null
    }

    /**
     * Starts the HUD service.
     *
     * The product rule is that permissions are requested only when the feature is switched on, so
     * the notification permission is requested here (never at launch) and a missing overlay
     * permission opens the system page instead of failing silently.
     */
    fun startHud(activity: Activity) {
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch {
        try {
        container.configRepository.load()
        if (activity.isFinishing || activity.isDestroyed) return@launch
        container.configRepository.update { it.copy(hudEnabled = true) }
        val overlayGranted = DisplayMode.fromWire(config.value.android.displayMode) != DisplayMode.OVERLAY ||
            container.permissionManager.state(EcpPermission.OVERLAY).granted
        if (!overlayGranted) {
            _pendingIntent.value = container.permissionManager.overlaySettingsIntent()
        }
        var notificationRequested = false
        if (!container.permissionManager.state(EcpPermission.NOTIFICATIONS).granted) {
            container.permissionManager.markRequested(EcpPermission.NOTIFICATIONS)
            notificationRequested =
                container.permissionManager.requestNotificationPermissionIfNeeded(activity)
        }
        val started = runCatching { HudServiceController.start(activity.applicationContext) }.getOrDefault(false)
        _message.value = when {
            !overlayGranted -> HomeMessage.OVERLAY_PERMISSION_REQUIRED
            !started -> HomeMessage.HUD_START_FAILED
            notificationRequested -> HomeMessage.NOTIFICATION_PERMISSION_REQUESTED
            else -> HomeMessage.HUD_STARTED
        }
        refreshPermissions()
        } finally { _starting.value = false }
        }
    }

    /** Stops the HUD service. */
    fun stopHud(context: Context) {
        viewModelScope.launch {
            val stopped = runCatching {
                container.configRepository.update { it.copy(hudEnabled = false) }
                HudServiceController.stop(context)
            }.isSuccess
            _message.value = if (stopped) HomeMessage.HUD_STOPPED else HomeMessage.HUD_START_FAILED
            refreshPermissions()
        }
    }

    /** Opens the system settings page for [permission] when the platform exposes one. */
    fun settingsIntentFor(permission: EcpPermission): Intent = when (permission) {
        EcpPermission.OVERLAY -> container.permissionManager.overlaySettingsIntent()
        EcpPermission.NOTIFICATIONS -> container.permissionManager.notificationSettingsIntent()
        else -> container.permissionManager.applicationDetailsIntent()
    }

    fun openPermissionSettings(activity: Activity, permission: EcpPermission) {
        if (permission == EcpPermission.LIVE_UPDATE) {
            container.islandRegistry.providerFor(IslandProviderKind.ANDROID_SYSTEM)?.requestAuthorization(activity)
        } else {
            activity.startActivity(settingsIntentFor(permission))
        }
    }

    /** True while the HUD service reports itself as running. */
    fun isRunningNow(): Boolean = runCatching {
        HudServiceController.isRunning(container.permissionManager.context())
    }.getOrDefault(false)

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
