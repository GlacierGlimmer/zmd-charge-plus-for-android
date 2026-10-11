package com.glacierglimmer.endfieldchargeplus.service

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.glacierglimmer.endfieldchargeplus.R
import com.glacierglimmer.endfieldchargeplus.core.i18n.BuiltInProfileLocalization
import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.metrics.ProfileDataValidation
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Called on the main thread. One incident is shared by the Activity and foreground HUD service. */
class HudDataWarnings(private val context: Context, private val foreground: () -> Boolean) {
    data class Notice(val name: String,val keys: List<String>,val profileId: String)
    private val notices=MutableStateFlow<Notice?>(null)
    val pending=notices.asStateFlow()
    private val incidents=com.glacierglimmer.endfieldchargeplus.core.metrics.WarningIncidentGate()
    private var dialog: AlertDialog?=null
    private var handingOff=false
    private val notificationId=10082
    fun check(profile: HudProfile,snapshot: MetricSnapshot) {
        if(snapshot.timestampMs==0L) return
        val missing=ProfileDataValidation.missing(profile,snapshot)
        if(missing.isEmpty()) { incidents.shouldPresent(profile.id,missing,SystemClock.elapsedRealtime(),false); if(notices.value?.profileId==profile.id) dismiss(); return }
        val canPresent=notices.value==null && context.getSystemService(PowerManager::class.java)?.isInteractive!=false
        if(!incidents.shouldPresent(profile.id,missing,SystemClock.elapsedRealtime(),canPresent)) return
        val notice=Notice(BuiltInProfileLocalization.displayName(profile,Strings.current()),missing,profile.id)
        notices.value=notice
        if(!foreground()) showBackground(notice)
    }
    private fun message(notice: Notice): String = Strings.t(
        "方案“${notice.name}”的数据不可用或无效。请检查 ROOT／权限、设备支持、所选窗口和数据源设置。",
        "Data for '${notice.name}' is unavailable or invalid. Check ROOT/permissions, device support, the selected window and data-source settings.")+"\n\n"+notice.keys.joinToString("\n")
    private fun showBackground(notice: Notice) {
        if(Settings.canDrawOverlays(context)) {
            try {
                val themed=ContextThemeWrapper(context,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                val alert=AlertDialog.Builder(themed).setTitle(Strings.t("HUD 数据提示","HUD data warning"))
                    .setMessage(message(notice)).setPositiveButton(Strings.t("知道了","OK")) { _,_ -> dismiss() }.create()
                alert.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                alert.setOnDismissListener { dialog=null; if(!handingOff) notices.value=null }
                dialog=alert; alert.show(); return
            } catch (_: RuntimeException) { dialog=null }
        }
        // Island mode may have notification permission without overlay permission. Keep the pending
        // notice for the next Activity opening, and use the OS heads-up channel in the meantime.
        runCatching {
            val manager=context.getSystemService(NotificationManager::class.java) ?: return
            val channel="ecp_data_warnings"
            manager.createNotificationChannel(NotificationChannel(channel,Strings.t("HUD 数据提示","HUD data warnings"),NotificationManager.IMPORTANCE_HIGH))
            val builder=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(Strings.t("HUD 数据提示","HUD data warning")).setContentText(message(notice))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message(notice))).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ERROR)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                builder.setContentIntent(PendingIntent.getActivity(context,notificationId,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.notify(notificationId,builder.build())
        }
    }
    fun onForegroundOpened() { handingOff=true; val old=dialog; dialog=null; old?.dismiss(); handingOff=false; context.getSystemService(NotificationManager::class.java)?.cancel(notificationId) }
    fun dismiss() {
        val old=dialog; dialog=null; old?.dismiss(); notices.value=null
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }
    fun stop() { dismiss(); incidents.clear() }
}