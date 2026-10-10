package com.glacierglimmer.endfieldchargeplus.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.glacierglimmer.endfieldchargeplus.R

/** No permission dialog at launch: the in-app prompt remains available if notifications are denied. */
object UpdateNotification {
    fun post(context: Context, result: UpdateResult, english: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("ecp_updates",
            if (english) "App updates" else "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 4701,
            Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateClient.RELEASES_URL)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "ecp_updates")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (english) "Endfield Charge Plus update available" else "Endfield Charge Plus 有新版本")
            .setContentText(if (english) "Found v${result.latestVersion}. Tap to view releases." else "发现 v${result.latestVersion}，点击查看更新。")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(4701, notification)
    }
}
