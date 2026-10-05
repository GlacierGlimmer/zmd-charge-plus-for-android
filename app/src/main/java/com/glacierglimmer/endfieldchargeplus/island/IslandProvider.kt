package com.glacierglimmer.endfieldchargeplus.island

import android.app.Activity
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import kotlinx.coroutines.CoroutineScope

/**
 * Platform island ("灵动岛 / 超级岛 / Live Update") abstraction.
 *
 * The settings UI, the foreground service and the HUD state pipeline only ever talk to this
 * interface, so a vendor backend can be added without touching the overlay or the data layer.
 * A provider must never pretend to be connected: when the platform forbids the scenario, the
 * implementation reports [IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM] or
 * [IslandAvailabilityState.NOT_AUTHORIZED] and the settings UI shows exactly that.
 */
interface IslandProvider {

    val kind: IslandProviderKind

    /** Stable id used in logs and settings. */
    val id: String

    /** Localization key of the provider name shown in settings. */
    val nameKey: String

    /** The provider is the recommended pick on this device. */
    fun isPreferred(): Boolean = false

    /** Static capability description; never claims more than the platform allows. */
    fun capabilities(): IslandCapabilities

    /** Last known availability including the exact reason when unavailable. */
    fun availability(): IslandAvailability

    /** Re-evaluates availability (system version, vendor SDK, authorization state). */
    suspend fun refreshAvailability(): IslandAvailability

    /**
     * Asks the platform/vendor for authorization when the backend exposes a request flow.
     * Returns true when an authorization UI was launched; the caller must re-check
     * [refreshAvailability] afterwards.
     */
    fun requestAuthorization(activity: Activity?): Boolean

    /** Begins publishing. Must be a no-op when [availability] is not usable. */
    fun start(scope: CoroutineScope)

    fun update(data: HudRenderData)

    fun stop()

    fun isRunning(): Boolean
}

/** Everything a caller needs to explain the current provider state to the user. */
data class IslandAvailability(
    val state: IslandAvailabilityState,
    /** Localization key describing the state in the current language. */
    val messageKey: String,
    /** Optional technical detail for the diagnostics page. */
    val detail: String = "",
) {
    val usable: Boolean
        get() = state == IslandAvailabilityState.AVAILABLE

    companion object {
        fun available(detail: String = "") =
            IslandAvailability(IslandAvailabilityState.AVAILABLE, "island_state_available", detail)

        fun unsupported(detail: String = "") =
            IslandAvailability(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, "island_state_unsupported", detail)

        fun needsAuthorization(detail: String = "") =
            IslandAvailability(IslandAvailabilityState.NOT_AUTHORIZED, "island_state_not_authorized", detail)

        fun needsVendorPermission(detail: String = "") =
            IslandAvailability(IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED, "island_state_vendor_permission", detail)

        fun unavailable(messageKey: String, detail: String = "") =
            IslandAvailability(IslandAvailabilityState.UNAVAILABLE, messageKey, detail)
    }
}

/** Why an island backend cannot be used, if it cannot. */
enum class IslandAvailabilityState {
    /** The provider can publish right now. */
    AVAILABLE,

    /** The Android version or the vendor ROM has no such API. */
    UNSUPPORTED_BY_PLATFORM,

    /** The API exists but this application has not been authorized by the vendor/user. */
    NOT_AUTHORIZED,

    /** The vendor requires a developer application/scenario review before the API unlocks. */
    VENDOR_PERMISSION_REQUIRED,

    /** Temporarily unavailable (system busy, notifications disabled, ...). */
    UNAVAILABLE,
}

/**
 * What a backend can actually render.
 *
 * Android's official promoted ongoing notification API does not allow arbitrary RemoteViews, so
 * providers must describe their real limits instead of approximating the overlay HUD.
 */
data class IslandCapabilities(
    val supportsTitle: Boolean,
    val supportsSubtitle: Boolean,
    val supportsProgress: Boolean,
    val supportsIcons: Boolean,
    val supportsMultipleLines: Boolean,
    val supportsCustomLayout: Boolean,
    val supportsContinuousUpdates: Boolean,
    val maxUpdateHz: Double,
    val experimental: Boolean = false,
    /** Localization keys of the platform limits, shown in the settings UI. */
    val limitationKeys: List<String> = emptyList(),
    /** Whether the backend can express the ECP "left/right" information split at all. */
    val supportsLeftRightSplit: Boolean = false,
)
