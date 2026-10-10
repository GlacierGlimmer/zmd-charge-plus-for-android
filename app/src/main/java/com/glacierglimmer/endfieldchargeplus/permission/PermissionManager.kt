package com.glacierglimmer.endfieldchargeplus.permission

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Every permission class the Android edition can need. */
enum class EcpPermission {
    /** `SYSTEM_ALERT_WINDOW` — required by the floating HUD only. */
    OVERLAY,

    /** `POST_NOTIFICATIONS` (Android 13+) — required to show the foreground notification. */
    NOTIFICATIONS,

    /** The foreground service itself (runtime state, not a runtime permission). */
    FOREGROUND_SERVICE,

    /** Network access for probe/DeepSeek/HTTP sources. */
    NETWORK,

    /** Boot restart, used only after the user explicitly enables it. */
    BOOT_START,

    /** Android promoted ongoing notification / live update eligibility. */
    LIVE_UPDATE,

}

/** Current state of one permission, with a localization key explaining it. */
data class PermissionState(
    val permission: EcpPermission,
    val granted: Boolean,
    val messageKey: String,
    val detail: String = "",
    /** True when the app can still send the user to a system settings page for it. */
    val canRequest: Boolean = false,
)

/**
 * Central permission handling.
 *
 * The product rule is explicit: permissions are requested only when the user turns on the feature
 * that needs them, never in one burst on first launch.
 */
interface PermissionManager {

    fun state(permission: EcpPermission): PermissionState

    fun all(): List<PermissionState>

    /** `ACTION_MANAGE_OVERLAY_PERMISSION` intent for this package. */
    fun overlaySettingsIntent(): Intent

    /** Application notification settings page. */
    fun notificationSettingsIntent(): Intent

    /** Application detail settings page (battery optimization guidance). */
    fun applicationDetailsIntent(): Intent

    /**
     * Requests `POST_NOTIFICATIONS` on Android 13+ when, and only when, the user starts the HUD.
     * Returns true when a request was launched.
     */
    fun requestNotificationPermissionIfNeeded(activity: Activity): Boolean

    /** Records that the user was already asked, so the UI can explain how to re-enable it. */
    fun markRequested(permission: EcpPermission)

    fun wasRequested(permission: EcpPermission): Boolean

    fun context(): Context
}
