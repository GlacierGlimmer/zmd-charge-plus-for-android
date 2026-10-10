package com.glacierglimmer.endfieldchargeplus.island

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.glacierglimmer.endfieldchargeplus.MainActivity
import com.glacierglimmer.endfieldchargeplus.R
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

/**
 * The official Android "live update" backend: a promoted ongoing notification.
 *
 * Research summary (details and source URLs in `docs/audit/03-island-platforms.md`):
 *  * the decision side of the public SDK 36 is `NotificationManager#canPostPromotedNotifications()`
 *    (user authorization) and `Notification#hasPromotableCharacteristics()` (shape check), plus
 *    `Notification.ProgressStyle` and `Notification.Builder#setShortCriticalText(String)`;
 *  * androidx.core 1.17.0 exposes the request side as well: `NotificationCompat.Builder
 *    #setRequestPromotedOngoing(boolean)`, `NotificationCompat#EXTRA_REQUEST_PROMOTED_ONGOING` and
 *    the helper `NotificationCompat#hasPromotableCharacteristics(Notification)`. The platform
 *    `Notification.Builder#setRequestPromotedOngoing(boolean)` itself is a flagged API that is absent
 *    from the SDK 36 stubs, so this provider sets the same documented extra through the public
 *    androidx constant instead of reflecting into the platform.
 *
 * The provider therefore posts an ordinary, correctly shaped foreground-service notification and
 * reports exactly what the platform says about it. It never claims that a notification *was*
 * promoted: promotion remains a platform decision, which is why this provider is `experimental`
 * and carries the `island_limit_scenario_restricted` limitation.
 */
class AndroidLiveUpdateProvider(
    private val context: Context,
    private val notificationHost: IslandNotificationHost,
) : IslandProvider {

    override val kind: IslandProviderKind = IslandProviderKind.ANDROID_SYSTEM

    override val id: String = ID

    override val nameKey: String = NAME_KEY

    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    @Volatile
    private var lastAvailability: IslandAvailability? = null

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var content: IslandContent = IslandContent()

    private var updates: IslandUpdateQueue? = null

    private val openApp by lazy {
        PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The notification shape the platform accepted as promotable. Android 16 requires a colourised
     * notification while Android 16 QPR1 forbids colourisation and requires the explicit
     * `android.requestPromotedOngoing` opt-in extra, so the provider probes both shapes once and
     * remembers the accepted one. `null` means neither shape is promotable on this ROM.
     */
    @Volatile
    private var promotableShape: PromotableShape? = null

    override fun capabilities(): IslandCapabilities = CAPABILITIES

    override fun availability(): IslandAvailability =
        lastAvailability ?: evaluate().also { lastAvailability = it }

    override suspend fun refreshAvailability(): IslandAvailability {
        ensureChannel()
        return evaluate().also { lastAvailability = it }
    }

    /**
     * Opens the system page that lets the user allow promoted notifications for this app
     * (`Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`, documented in the AOSP KDoc of
     * `NotificationManager#canPostPromotedNotifications`).
     */
    override fun requestAuthorization(activity: Activity?): Boolean {
        if (activity == null || Build.VERSION.SDK_INT < MIN_PROMOTED_API_LEVEL) return false
        val actions = listOf(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        for (action in actions) {
            try {
                activity.startActivity(Intent(action)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID))
                return true
            } catch (t: Throwable) {
                AppLog.w(TAG, "cannot open notification settings: $action", t)
            }
        }
        return false
    }

    /** Publishes the first frame. A no-op (with a log line) when the backend is not usable. */
    override fun start(scope: CoroutineScope) {
        if (running) return
        val availability = refreshAvailabilityBlocking()
        if (!availability.usable) {
            AppLog.w(TAG, "start refused: state=${availability.state} key=${availability.messageKey} ${availability.detail}")
            return
        }
        ensureChannel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            lastError = "publishing refused: API ${Build.VERSION.SDK_INT} has no promoted ongoing notification API"
            return
        }
        running = true
        lastError = ""
        updates = IslandUpdateQueue(scope, intervalMs = 5_000L) { frame -> publishFrame(frame) }
        AppLog.i(TAG, "live-update publisher started (id=$NOTIFICATION_ID, channel=$CHANNEL_ID)")
    }

    /** Pushes one HUD frame. Dropped when nothing is running or when the platform refuses it. */
    override fun update(data: HudRenderData) {
        content = IslandHudMapper.mapLiveUpdate(data)
        if (running && !content.isEmpty) updates?.offer(content)
    }

    private fun publishFrame(frame: IslandContent) {
        if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        try {
            notificationHost.update(NOTIFICATION_ID, buildApi36Notification(frame, promotableShape ?: PromotableShape.ANDROID_16))
            if (!running) notificationHost.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            running = false
            lastError = "update failed: ${describe(t)}"
            lastAvailability = IslandAvailability.unavailable(KEY_PUBLISH_FAILED, lastError)
            AppLog.e(TAG, "updating the live-update notification failed", t)
            updates?.stop()
        }
    }

    override fun stop() {
        updates?.stop()
        updates = null
        if (!running) return
        running = false
        try {
            notificationHost.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            AppLog.w(TAG, "cancelling the live-update notification failed", t)
        }
    }

    override fun isRunning(): Boolean = running

    /** Last publish/update failure, surfaced in the diagnostics page. */
    fun lastError(): String = lastError

    private fun refreshAvailabilityBlocking(): IslandAvailability = evaluate().also { lastAvailability = it }

    /**
     * Builds the notification for publishing. Annotated and only ever called behind an explicit
     * `Build.VERSION.SDK_INT` check so no code path can touch the API 36 builder on an older device.
     */
    @RequiresApi(MIN_PROMOTED_API_LEVEL)
    private fun buildApi36Notification(content: IslandContent, shape: PromotableShape): Notification.Builder =
        newBuilder(content, shape)

    private fun evaluate(): IslandAvailability {
        val channel = notificationManager?.getNotificationChannel(CHANNEL_ID)
        val probe = AndroidLiveUpdateProbe(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsEnabled = notificationHost.areNotificationsEnabled(),
            channelBlocked = channel?.importance == NotificationManager.IMPORTANCE_NONE,
            canPostPromoted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) canPostPromotedNotifications() else false,
            failureDetail = lastError,
        )
        AndroidLiveUpdatePolicy.preconditions(probe)?.let { return it }

        val promotable = resolvePromotableShape()
        return if (promotable != null) {
            IslandAvailability.available(
                "API ${Build.VERSION.SDK_INT}: promoted notifications allowed for ${context.packageName}; " +
                    "the system still decides per notification whether to promote it",
            )
        } else {
            IslandAvailability.unavailable(KEY_NOT_PROMOTABLE, lastError)
        }
    }

    /**
     * Asks the platform which notification shape it considers promotable, using the public
     * `NotificationCompat#hasPromotableCharacteristics(Notification)` helper (it delegates to the API
     * 36 platform method and returns `false` on older releases). Both candidate shapes are probed and
     * the accepted one is remembered.
     *
     * @return the accepted shape, or `null` when neither shape is promotable on this ROM.
     */
    private fun resolvePromotableShape(): PromotableShape? {
        promotableShape?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val probeContent = IslandContent(title = "ECP", subtitle = "promotion probe")
        for (shape in PromotableShape.CANDIDATES) {
            val accepted = try {
                NotificationCompat.hasPromotableCharacteristics(newBuilder(probeContent, shape).build())
            } catch (t: Throwable) {
                lastError = "hasPromotableCharacteristics() failed: ${describe(t)}"
                false
            }
            if (accepted) {
                promotableShape = shape
                AppLog.i(TAG, "promotable notification shape accepted: colorized=${shape.colorized} requestPromoted=${shape.requestPromoted}")
                return shape
            }
        }
        if (lastError.isBlank()) {
            lastError = "NotificationCompat#hasPromotableCharacteristics() is false for both documented shapes " +
                "(Android 16: colourised; Android 16 QPR1: plain + android.requestPromotedOngoing)"
        }
        return null
    }

    @RequiresApi(MIN_PROMOTED_API_LEVEL)
    private fun canPostPromotedNotifications(): Boolean? {
        val manager = notificationManager ?: run {
            lastError = "NotificationManager unavailable"
            return null
        }
        return try {
            manager.canPostPromotedNotifications()
        } catch (t: Throwable) {
            lastError = "canPostPromotedNotifications() failed: ${describe(t)}"
            null
        }
    }

    /**
     * Builds the candidate notification. The shape follows the platform rules found in AOSP
     * `Notification#hasPromotableCharacteristics()`: ongoing, titled, not a group summary, no custom
     * views, and a promotable style (`ProgressStyle`, `BigTextStyle` or none). The opt-in extra is the
     * public `NotificationCompat#EXTRA_REQUEST_PROMOTED_ONGOING` constant, i.e. exactly the value
     * `NotificationCompat.Builder#setRequestPromotedOngoing(true)` writes.
     */
    @RequiresApi(MIN_PROMOTED_API_LEVEL)
    private fun newBuilder(content: IslandContent, shape: PromotableShape): Notification.Builder {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(content.title.ifBlank { FALLBACK_TITLE })
            .setContentText(content.bodyText.ifBlank { content.shortText })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setColorized(shape.colorized)

        if (shape.requestPromoted) {
            builder.addExtras(
                Bundle().apply { putBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING, true) },
            )
        }

        builder.setShortCriticalText(content.shortText)
        builder.setStyle(Notification.BigTextStyle().bigText(content.bodyText))
        val progress = content.progressPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        builder.setProgress(100, progress.roundToInt(), false)
        return builder
    }

    private fun smallIconRes(): Int =
        try {
            R.drawable.ic_launcher_monochrome
        } catch (t: Throwable) {
            android.R.drawable.stat_sys_download
        }

    private fun ensureChannel() {
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.description = CHANNEL_DESCRIPTION
        manager.createNotificationChannel(channel)
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"

    companion object {
        /** Stable provider id used in logs and settings. */
        const val ID: String = "android_system_live_update"

        /** Localization key of the provider name. */
        const val NAME_KEY: String = "island_provider_android_system"

        /** Notification channel used by the live-update session. */
        const val CHANNEL_ID: String = "ecp_island_live_update"

        private const val CHANNEL_NAME: String = "HUD live update"
        private const val CHANNEL_DESCRIPTION: String = "Promoted ongoing notification carrying the HUD status"

        /** Notification id of the single live-update notification. */
        const val NOTIFICATION_ID: Int = 0xEC9A01

        /**
         * Android 16 / API 36 is the first SDK that contains the promoted ongoing notification
         * APIs; older releases have no equivalent.
         */
        const val MIN_PROMOTED_API_LEVEL: Int = 36

        /** Declared in the manifest; kept here so a missing manifest entry is detectable in code. */
        const val PERMISSION_POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"

        /** Message keys consumed by the settings page. */
        const val KEY_NOTIFICATIONS_DISABLED: String = "island_state_notifications_disabled"
        const val KEY_CHANNEL_DISABLED: String = "island_state_channel_disabled"
        const val KEY_PROMOTION_CHECK_FAILED: String = "island_state_promotion_check_failed"
        const val KEY_NOT_AUTHORIZED: String = "island_state_not_authorized"
        const val KEY_NOT_PROMOTABLE: String = "island_state_not_promotable"
        const val KEY_PUBLISH_FAILED: String = "island_state_publish_failed"

        private const val TAG: String = "IslandAndroid"
        private const val FALLBACK_TITLE: String = "Endfield Charge Plus"

        /** What the Android promoted notification can really express. */
        val CAPABILITIES: IslandCapabilities = IslandCapabilities(
            supportsTitle = true,
            supportsSubtitle = true,
            supportsProgress = true,
            supportsIcons = false,
            supportsMultipleLines = false,
            supportsCustomLayout = false,
            supportsContinuousUpdates = false,
            maxUpdateHz = 0.2,
            experimental = true,
            limitationKeys = listOf(
                "island_limit_no_custom_layout",
                "island_limit_no_left_right_split",
                "island_limit_scenario_restricted",
                "island_limit_throttled_updates",
            ),
            supportsLeftRightSplit = false,
        )
    }
}

/**
 * One candidate notification shape for the platform's promotability check.
 *
 * The two documented Android 16 rule sets are mutually exclusive, so the provider probes both and
 * remembers the one the platform accepted.
 */
data class PromotableShape(
    /** Whether `Notification.Builder#setColorized(true)` is requested. */
    val colorized: Boolean,
    /** Whether the `android.requestPromotedOngoing` opt-in extra is set. */
    val requestPromoted: Boolean,
) {
    companion object {
        /** Android 16 (API 36) rule: ongoing, titled, colourised notification. */
        val ANDROID_16: PromotableShape = PromotableShape(colorized = true, requestPromoted = false)

        /** Android 16 QPR1 rule: plain notification plus the explicit promotion opt-in. */
        val ANDROID_16_QPR1: PromotableShape = PromotableShape(colorized = false, requestPromoted = true)

        /** Prefer the current opt-in shape; retain the original Android 16 rule as a fallback. */
        val CANDIDATES: List<PromotableShape> = listOf(ANDROID_16_QPR1, ANDROID_16)
    }
}

/** Pure decision input for [AndroidLiveUpdatePolicy], free of Android types so it is testable. */
data class AndroidLiveUpdateProbe(
    val sdkInt: Int,
    val notificationsEnabled: Boolean,
    val channelBlocked: Boolean,
    val canPostPromoted: Boolean?,
    val failureDetail: String = "",
)

/**
 * The Android promoted-notification eligibility policy.
 *
 * Every branch maps to a user visible state plus an exact message key; nothing here can claim
 * availability that the platform did not confirm.
 */
object AndroidLiveUpdatePolicy {

    /** Returns the blocking availability, or `null` when all preconditions hold. */
    fun preconditions(probe: AndroidLiveUpdateProbe): IslandAvailability? = when {
        probe.sdkInt < AndroidLiveUpdateProvider.MIN_PROMOTED_API_LEVEL ->
            IslandAvailability.unsupported(
                "promoted ongoing notifications require Android 16 (API 36); this device reports API ${probe.sdkInt}",
            )

        !probe.notificationsEnabled ->
            IslandAvailability.unavailable(
                AndroidLiveUpdateProvider.KEY_NOTIFICATIONS_DISABLED,
                "notifications are disabled for this app (POST_NOTIFICATIONS or the app notification switch)",
            )

        probe.channelBlocked ->
            IslandAvailability.unavailable(
                AndroidLiveUpdateProvider.KEY_CHANNEL_DISABLED,
                "notification channel ${AndroidLiveUpdateProvider.CHANNEL_ID} is blocked by the user",
            )

        probe.canPostPromoted == null ->
            IslandAvailability.unavailable(
                AndroidLiveUpdateProvider.KEY_PROMOTION_CHECK_FAILED,
                probe.failureDetail.ifBlank { "NotificationManager#canPostPromotedNotifications() is unavailable" },
            )

        !probe.canPostPromoted ->
            IslandAvailability(
                IslandAvailabilityState.NOT_AUTHORIZED,
                AndroidLiveUpdateProvider.KEY_NOT_AUTHORIZED,
                "the user has not enabled promoted notifications for this app " +
                    "(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)",
            )

        else -> null
    }
}
