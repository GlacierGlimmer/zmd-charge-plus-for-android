package com.glacierglimmer.endfieldchargeplus.island

import android.app.Notification

/**
 * The only channel through which an [IslandProvider] is allowed to reach the notification stack.
 *
 * The provider builds the [Notification.Builder] (it owns the channel, the content and the promoted
 * shape) and the owner of the foreground service decides when the notification is actually
 * published. That split exists for one reason: no island provider may ever publish on its own, so a
 * provider can never report a connection the running service did not make.
 */
interface IslandNotificationHost {

    /** Publishes a new notification. Re-publishing the same [id] replaces the previous one. */
    fun publish(id: Int, builder: Notification.Builder)

    /** Updates an already published notification; must not silently drop the request. */
    fun update(id: Int, builder: Notification.Builder)

    /** Removes the notification, ending the island/live-update session. */
    fun cancel(id: Int)

    /**
     * Whether the application may show notifications at all (`POST_NOTIFICATIONS` and the
     * per-app notification switch). A provider must treat `false` as "cannot publish".
     */
    fun areNotificationsEnabled(): Boolean

    companion object {
        /**
         * Host used when no foreground service owns the notification stack (settings preview,
         * unit tests). It is deliberately *disabled* rather than permissive.
         */
        val Disabled: IslandNotificationHost = DisabledIslandNotificationHost
    }
}

/**
 * No-op [IslandNotificationHost].
 *
 * [areNotificationsEnabled] returns `false` on purpose: with this host every provider evaluates to
 * [IslandAvailabilityState.UNAVAILABLE], so the settings page shows 不可用 and no code path can
 * present a plain notification as a working island backend.
 */
object DisabledIslandNotificationHost : IslandNotificationHost {

    override fun publish(id: Int, builder: Notification.Builder) = Unit

    override fun update(id: Int, builder: Notification.Builder) = Unit

    override fun cancel(id: Int) = Unit

    override fun areNotificationsEnabled(): Boolean = false
}
