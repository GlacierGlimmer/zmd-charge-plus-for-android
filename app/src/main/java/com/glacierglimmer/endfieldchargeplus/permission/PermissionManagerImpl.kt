package com.glacierglimmer.endfieldchargeplus.permission

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import java.util.Locale

/**
 * [PermissionManager] for a real Android device.
 *
 * Product rule: nothing is requested implicitly. The only runtime request this class can launch is
 * `POST_NOTIFICATIONS`, and only from [requestNotificationPermissionIfNeeded], which the UI calls
 * when the user actually starts the HUD. Everything else is either a declarative (normal)
 * permission that Android grants at install time, or an operating-system state that is reported
 * here and only changed by the user in system settings.
 *
 * The class stays decoupled from the other layers through three optional providers:
 *
 * @param liveUpdateEligible whether this device can use the promoted ongoing notification /
 *   live-update path (supplied by the island layer). Defaults to `false`: eligibility is never
 *   claimed without proof.
 * @param vendorIslandGranted whether Xiaomi HyperIsland has been authorised. Defaults to `false`.
 * @param foregroundServiceRunning whether the HUD foreground service is currently running. The
 *   default inspects this process' importance, which is `FOREGROUND_SERVICE` exactly while a
 *   foreground service owns it.
 */
class PermissionManagerImpl(
    private val context: Context,
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    private val liveUpdateEligible: () -> Boolean = { false },
    private val vendorIslandGranted: () -> Boolean = { false },
    private val foregroundServiceRunning: () -> Boolean = { isProcessForeground(context) },
) : PermissionManager {

    override fun state(permission: EcpPermission): PermissionState = when (permission) {
        EcpPermission.OVERLAY -> {
            val granted = canDrawOverlays()
            PermissionState(
                permission = permission,
                granted = granted,
                messageKey = if (granted) KEY_OVERLAY_GRANTED else KEY_OVERLAY_REQUIRED,
                detail = if (granted) "Draw over other apps is allowed." else "SYSTEM_ALERT_WINDOW was not granted.",
                canRequest = !granted,
            )
        }

        EcpPermission.NOTIFICATIONS -> {
            val granted = notificationsGranted()
            PermissionState(
                permission = permission,
                granted = granted,
                messageKey = if (granted) KEY_NOTIFICATIONS_GRANTED else KEY_NOTIFICATIONS_REQUIRED,
                detail = when {
                    granted -> "Notification permission is granted."
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Android < 13 needs no runtime notification permission."
                    else -> "POST_NOTIFICATIONS is required by the foreground service notification."
                },
                canRequest = !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            )
        }

        EcpPermission.FOREGROUND_SERVICE -> {
            val declared = declared(Manifest.permission.FOREGROUND_SERVICE)
            val running = safeCall(foregroundServiceRunning, false)
            PermissionState(
                permission = permission,
                granted = declared,
                messageKey = if (declared) KEY_FOREGROUND_SERVICE_DECLARED else KEY_FOREGROUND_SERVICE_MISSING,
                detail = "declared=$declared, running=$running",
                canRequest = false,
            )
        }

        EcpPermission.NETWORK -> {
            val declared = declared(Manifest.permission.ACCESS_NETWORK_STATE) && declared(Manifest.permission.INTERNET)
            val connected = isConnected()
            PermissionState(
                permission = permission,
                granted = declared && connected,
                messageKey = when {
                    !declared -> KEY_NETWORK_MISSING
                    connected -> KEY_NETWORK_CONNECTED
                    else -> KEY_NETWORK_OFFLINE
                },
                detail = if (connected) "An internet-capable network is active." else "No internet-capable network.",
                canRequest = !declared,
            )
        }

        EcpPermission.BOOT_START -> {
            val declared = declared(Manifest.permission.RECEIVE_BOOT_COMPLETED)
            PermissionState(
                permission = permission,
                granted = declared,
                messageKey = if (declared) KEY_BOOT_START_DECLARED else KEY_BOOT_START_MISSING,
                detail = "RECEIVE_BOOT_COMPLETED declared=$declared (enabled per user setting).",
                canRequest = false,
            )
        }

        EcpPermission.LIVE_UPDATE -> {
            val granted = safeCall(liveUpdateEligible, false)
            PermissionState(
                permission = permission,
                granted = granted,
                messageKey = if (granted) KEY_LIVE_UPDATE_GRANTED else KEY_LIVE_UPDATE_UNAVAILABLE,
                detail = "Promoted ongoing notification supported=$granted.",
                canRequest = false,
            )
        }

        EcpPermission.VENDOR_ISLAND -> {
            val granted = safeCall(vendorIslandGranted, false)
            PermissionState(
                permission = permission,
                granted = granted,
                messageKey = if (granted) KEY_VENDOR_ISLAND_GRANTED else KEY_VENDOR_ISLAND_REQUIRED,
                detail = "Vendor island backend authorised=$granted.",
                canRequest = !granted,
            )
        }
    }

    override fun all(): List<PermissionState> = EcpPermission.entries.map { state(it) }

    override fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())

    override fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    override fun applicationDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    override fun requestNotificationPermissionIfNeeded(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (notificationsGranted()) return false
        return try {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_POST_NOTIFICATIONS,
            )
            markRequested(EcpPermission.NOTIFICATIONS)
            AppLog.i(TAG, "Requested POST_NOTIFICATIONS")
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "Could not request POST_NOTIFICATIONS", t)
            false
        }
    }

    override fun markRequested(permission: EcpPermission) {
        runCatching { preferences.edit().putBoolean(requestKey(permission), true).apply() }
            .onFailure { AppLog.w(TAG, "Could not record the '${permission.name}' request", it) }
    }

    override fun wasRequested(permission: EcpPermission): Boolean =
        runCatching { preferences.getBoolean(requestKey(permission), false) }.getOrDefault(false)

    override fun context(): Context = context

    // ------------------------------------------------------------------------------------------
    // checks
    // ------------------------------------------------------------------------------------------

    private fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(context) }
            .onFailure { AppLog.w(TAG, "Could not read the overlay permission state", it) }
            .getOrDefault(false)

    /** `POST_NOTIFICATIONS` only exists from Android 13 on; older releases count as granted. */
    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }.onFailure { AppLog.w(TAG, "Could not read the notification permission state", it) }
            .getOrDefault(false)
    }

    private fun isConnected(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.onFailure { AppLog.w(TAG, "Connectivity check failed", it) }.getOrDefault(false)

    /** True when the manifest declares [permission] (normal permissions are granted at install). */
    private fun declared(permission: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    }.onFailure { AppLog.w(TAG, "Could not read the manifest permissions", it) }.getOrDefault(false)

    private fun packageUri(): Uri = Uri.parse("package:${context.packageName}")

    private fun requestKey(permission: EcpPermission): String =
        "requested_${permission.name.lowercase(Locale.US)}"

    private fun <T> safeCall(provider: () -> T, fallback: T): T =
        runCatching { provider() }.getOrElse {
            AppLog.w(TAG, "A permission state provider failed", it)
            fallback
        }

    companion object {
        private const val TAG = "Permissions"
        private const val PREFERENCES_NAME = "ecp_permissions"

        /** Request code the Activity must forward back to [requestNotificationPermissionIfNeeded]. */
        const val REQUEST_CODE_POST_NOTIFICATIONS = 4101

        // Localization keys consumed by the settings UI; each is stable and language independent.
        const val KEY_OVERLAY_GRANTED = "permission.overlay.granted"
        const val KEY_OVERLAY_REQUIRED = "permission.overlay.required"
        const val KEY_NOTIFICATIONS_GRANTED = "permission.notifications.granted"
        const val KEY_NOTIFICATIONS_REQUIRED = "permission.notifications.required"
        const val KEY_FOREGROUND_SERVICE_DECLARED = "permission.foreground_service.declared"
        const val KEY_FOREGROUND_SERVICE_MISSING = "permission.foreground_service.missing"
        const val KEY_NETWORK_CONNECTED = "permission.network.connected"
        const val KEY_NETWORK_OFFLINE = "permission.network.offline"
        const val KEY_NETWORK_MISSING = "permission.network.missing"
        const val KEY_BOOT_START_DECLARED = "permission.boot_start.declared"
        const val KEY_BOOT_START_MISSING = "permission.boot_start.missing"
        const val KEY_LIVE_UPDATE_GRANTED = "permission.live_update.granted"
        const val KEY_LIVE_UPDATE_UNAVAILABLE = "permission.live_update.unavailable"
        const val KEY_VENDOR_ISLAND_GRANTED = "permission.vendor_island.granted"
        const val KEY_VENDOR_ISLAND_REQUIRED = "permission.vendor_island.required"
    }
}

/** Mirrors `android.app.ActivityManager` without adding an Android dependency to callers. */
private fun isProcessForeground(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (manager == null) {
        false
    } else {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }
}.getOrDefault(false)
