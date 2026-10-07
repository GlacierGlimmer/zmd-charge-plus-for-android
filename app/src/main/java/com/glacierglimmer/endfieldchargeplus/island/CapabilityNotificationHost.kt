package com.glacierglimmer.endfieldchargeplus.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/** Reads real notification permissions for Settings; publishing still belongs to the service. */
class CapabilityNotificationHost(context: Context) : IslandNotificationHost {
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun areNotificationsEnabled(): Boolean = manager?.areNotificationsEnabled() == true
    override fun publish(id: Int, builder: Notification.Builder): Unit = error("Settings cannot publish island notifications")
    override fun update(id: Int, builder: Notification.Builder): Unit = error("Settings cannot update island notifications")
    override fun cancel(id: Int): Unit = error("Settings cannot cancel island notifications")
}
