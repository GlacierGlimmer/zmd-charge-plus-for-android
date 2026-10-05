package com.glacierglimmer.endfieldchargeplus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glacierglimmer.endfieldchargeplus.R
import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog

/**
 * Builds the foreground notification of the HUD service.
 *
 * Localization uses the shared core string API with the language the rest of the application is
 * currently rendering in, so the notification follows an in-app language switch even when the system
 * locale is different (the product requirement is that switching to English leaves no Chinese text
 * anywhere — including the notification, the foreground service and the channel name).
 *
 * No `RemoteViews` are used: the notification is a plain ongoing notification with one action.
 */
object HudNotificationFactory {

    /** Channel id; also the channel island providers must use when they publish through the host. */
    const val CHANNEL_ID = "ecp_hud_service"

    /** Stable notification id, kept away from the island provider ids. */
    const val NOTIFICATION_ID = 0xEC9

    private const val STOP_REQUEST_CODE = 0xEC9
    private const val CONTENT_REQUEST_CODE = 0xECA
    private const val TAG = "HudNotification"

    /**
     * Creates or updates the channel in [language].
     *
     * The channel is re-created on every call instead of returning early: Android 26+ updates the
     * name and description of an existing channel this way, which is what keeps the channel text in
     * sync after a language change (importance and sound cannot change, and are never changed here).
     */
    fun ensureChannel(context: Context, language: UiLanguage) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        Strings.setLanguage(language)
        val channel = NotificationChannel(
            CHANNEL_ID,
            Strings.t("HUD 服务", "HUD service"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = Strings.t(
                "维持终末地充电增强的悬浮 HUD 运行。",
                "Keeps the Endfield Charge Plus overlay HUD running.",
            )
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds the "HUD is running" notification.
     *
     * The action stops the service through [HudServiceController.ACTION_STOP]; the content intent
     * opens the settings activity, resolved through the package manager so this file does not depend
     * on the activity class itself.
     */
    fun build(context: Context, language: UiLanguage): Notification {
        ensureChannel(context, language)
        val stopIntent = Intent(context, HudForegroundService::class.java)
            .setAction(HudServiceController.ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            context,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPendingIntent = launchIntent(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(Strings.t("终末地充电增强正在运行", "Endfield Charge Plus is running"))
            .setContentText(Strings.t("悬浮 HUD 已启用，点按可打开设置。", "The floating HUD is active. Tap to open settings."))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                Strings.t("关闭 HUD", "Stop HUD"),
                stopPendingIntent,
            )
        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent)
        }
        return builder.build()
    }

    private fun launchIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            AppLog.d(TAG, "No launcher intent for ${context.packageName}; notification has no content intent")
            return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** True when the platform will actually show the notification (Android 13+ permission). */
    fun areNotificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
}
