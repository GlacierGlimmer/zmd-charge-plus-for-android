package com.glacierglimmer.endfieldchargeplus.service

import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.island.IslandAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the settings UI and the diagnostics page need to know about the running HUD.
 *
 * The state is process-wide because the HUD service, the `MainActivity` and the Compose preview all
 * live in the same process: the service publishes, everybody else observes.
 *
 * @param serviceRunning true while [HudForegroundService] is alive.
 * @param displayMode the output mode the user selected.
 * @param overlayShowing true while the overlay window is actually attached.
 * @param islandProviderId id of the island backend currently publishing, or null.
 * @param islandAvailability last availability reported by the island backend, or null when the
 *   overlay mode is active.
 * @param activeProfileId the scheme that is currently rendered (the cycled one when auto-cycling).
 * @param repositoryRunning true while the shared metric repository is sampling.
 * @param lastError human-readable diagnostic text of the last failure (missing overlay permission,
 *   a refused foreground-service start, …); null when everything is healthy.
 */
data class HudRuntimeStatus(
    val serviceRunning: Boolean = false,
    val displayMode: DisplayMode = DisplayMode.OVERLAY,
    val overlayShowing: Boolean = false,
    val islandProviderId: String? = null,
    val islandAvailability: IslandAvailability? = null,
    val activeProfileId: String = "",
    val repositoryRunning: Boolean = false,
    val lastError: String? = null,
)

/**
 * The process-wide observable HUD status.
 *
 * `publish` replaces the whole status; `update` performs an atomic read-modify-write, which is what
 * the overlay controller uses so it can never clobber a field owned by the service.
 */
object HudRuntimeState {

    private val mutableStatus = MutableStateFlow(HudRuntimeStatus())

    /** Always-current status. Emits immediately, so a late subscriber never misses the state. */
    val status: StateFlow<HudRuntimeStatus> = mutableStatus.asStateFlow()

    /** Replaces the status completely. */
    fun publish(status: HudRuntimeStatus) {
        mutableStatus.value = status
    }

    /** Atomically derives a new status from the current one. */
    fun update(transform: (HudRuntimeStatus) -> HudRuntimeStatus) {
        mutableStatus.update(transform)
    }

    /** Resets to the "nothing is running" status; used when the service stops for good. */
    fun reset() {
        publish(HudRuntimeStatus())
    }
}
