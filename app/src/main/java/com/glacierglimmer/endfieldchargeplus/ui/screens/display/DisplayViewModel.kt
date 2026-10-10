package com.glacierglimmer.endfieldchargeplus.ui.screens.display

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.island.IslandProvider
import com.glacierglimmer.endfieldchargeplus.island.IslandProviderStatus
import com.glacierglimmer.endfieldchargeplus.permission.EcpPermission
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Display page state: where and how the HUD is drawn (overlay) and which island backend is used.
 *
 * Every write goes through `configRepository.update`; island availability is read from the provider
 * registry so the page always shows the platform truth instead of an optimistic guess.
 */
class DisplayViewModel(private val container: EcpContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config

    val islands: StateFlow<List<IslandProviderStatus>> = container.islandRegistry.statuses

    private val _pendingIntent = MutableStateFlow<Intent?>(null)
    val pendingIntent: StateFlow<Intent?> = _pendingIntent.asStateFlow()

    private val _authorizationLaunched = MutableStateFlow(false)
    val authorizationLaunched: StateFlow<Boolean> = _authorizationLaunched.asStateFlow()

    fun consumeIntent() {
        _pendingIntent.value = null
    }

    fun consumeAuthorizationMessage() {
        _authorizationLaunched.value = false
    }

    /** Re-evaluates native live-update support and authorization. */
    fun refreshIslands() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.islandRegistry.refreshAll() }
        }
    }

    fun isOverlayGranted(): Boolean = container.permissionManager.state(EcpPermission.OVERLAY).granted

    /** Opens native notification authorization settings. */
    fun requestAuthorization(provider: IslandProvider, activity: Activity?) {
        val launched = runCatching { provider.requestAuthorization(activity) }.getOrDefault(false)
        _authorizationLaunched.value = launched
        if (launched) refreshIslands()
    }

    /** Master switch; enabling the overlay asks for the system permission instead of failing. */
    fun setHudEnabled(enabled: Boolean) {
        update { it.copy(hudEnabled = enabled) }
        if (enabled
            && DisplayMode.fromWire(config.value.android.displayMode) == DisplayMode.OVERLAY
            && !isOverlayGranted()
        ) {
            _pendingIntent.value = container.permissionManager.overlaySettingsIntent()
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        update { config -> config.copy(android = config.android.copy(displayMode = mode.wire, islandProvider = IslandProviderKind.ANDROID_SYSTEM.wire)) }
        if (mode == DisplayMode.OVERLAY && !isOverlayGranted()) {
            _pendingIntent.value = container.permissionManager.overlaySettingsIntent()
        }
    }

    fun setAlwaysVisible(enabled: Boolean) =
        update { it.copy(android = it.android.copy(alwaysVisible = enabled)) }

    fun setClickThrough(enabled: Boolean) =
        update { it.copy(android = it.android.copy(clickThrough = enabled)) }

    fun setAvoidCutout(enabled: Boolean) =
        update { it.copy(android = it.android.copy(avoidCutout = enabled)) }

    fun setStartOnBoot(enabled: Boolean) =
        update { it.copy(android = it.android.copy(startOnBoot = enabled)) }

    fun setGlobalScale(scale: Double) =
        update { it.copy(globalScale = scale.coerceIn(0.4, 1.4)) }

    fun setHudOpacity(opacity: Double) =
        update { it.copy(hudOpacity = opacity.coerceIn(0.10, 1.0)) }

    fun setPositionMode(mode: HudPositionMode) = update { it.copy(positionMode = mode.wire, android = it.android.copy(useDraggedPosition = false)) }

    fun setHudPosition(position: HudPosition) = update { it.copy(hudPosition = position.wire, android = it.android.copy(useDraggedPosition = false)) }
    fun restoreAnchor() = update { it.copy(android = it.android.copy(useDraggedPosition = false)) }

    fun setOffsetX(value: Int) = update { it.copy(hudOffsetX = value.coerceIn(-10_000, 10_000), android = it.android.copy(useDraggedPosition = false)) }

    fun setOffsetY(value: Int) = update { it.copy(hudOffsetY = value.coerceIn(-10_000, 10_000), android = it.android.copy(useDraggedPosition = false)) }

    fun setCustomX(value: Int) = update { it.copy(hudCustomX = value.coerceIn(-10_000, 20_000), android = it.android.copy(useDraggedPosition = false)) }

    fun setCustomY(value: Int) = update { it.copy(hudCustomY = value.coerceIn(-10_000, 20_000), android = it.android.copy(useDraggedPosition = false)) }

    /** Animation mode of the active scheme (the per-scheme setting the desktop edition exposes). */
    fun setAnimationMode(mode: AnimationMode) {
        val profile = config.value.customHud.activeProfile() ?: return
        updateActiveProfile(profile) { it.copy(animationMode = mode.wire) }
    }

    fun activeProfile(): HudProfile? = config.value.customHud.activeProfile()

    private fun updateActiveProfile(profile: HudProfile, transform: (HudProfile) -> HudProfile) {
        update { ProfileEdits.updateProfile(it, profile.id, transform) }
    }

    private fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DisplayViewModel(container) }
        }
    }
}
