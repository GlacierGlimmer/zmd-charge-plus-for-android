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
import android.widget.CheckBox
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private val ignored=context.getSharedPreferences("hud_data_warning_preferences",Context.MODE_PRIVATE)
    private val warningPreferences=com.glacierglimmer.endfieldchargeplus.core.metrics.DataWarningPreferences(
        read={ignored.getStringSet("ignored_variables",emptySet()).orEmpty().toSet()},
        write={updated -> ignored.edit().putStringSet("ignored_variables",updated).commit()})
    private var dialog: AlertDialog?=null
    private var handingOff=false
    private val notificationId=10082
    fun check(profile: HudProfile,snapshot: MetricSnapshot) {
        if(snapshot.timestampMs==0L) return
        val missing=warningPreferences.filter(profile.id,ProfileDataValidation.missing(profile,snapshot))
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
                val density=context.resources.displayMetrics.density
                val content=LinearLayout(themed).apply { orientation=LinearLayout.VERTICAL; val pad=(24*density).toInt(); setPadding(pad,0,pad,pad) }
                content.addView(ScrollView(themed).apply { addView(TextView(themed).apply { text=message(notice) }) },LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
                val footer=LinearLayout(themed).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
                val checkbox=CheckBox(themed).apply { text=Strings.t("此数据不再提示","Don't warn about this data again"); tag="ignore-current-data-warning" }
                checkbox.setOnCheckedChangeListener { _,checked -> if(checked && !ignore(notice)) checkbox.isChecked=false }
                footer.addView(checkbox,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
                footer.addView(Button(themed).apply { text=Strings.t("知道了","OK"); setOnClickListener { dismiss() } })
                content.addView(footer)
                val alert=AlertDialog.Builder(themed).setTitle(Strings.t("HUD 数据提示","HUD data warning")).setView(content).create()
                alert.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                alert.setOnDismissListener { dialog=null; if(!handingOff) notices.value=null }
                dialog=alert; alert.show()
                alert.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,(context.resources.displayMetrics.heightPixels*.6f).toInt())
                return
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
    fun ignore(notice: Notice): Boolean {
        if(!warningPreferences.ignore(notice.profileId,notice.keys)) {
            Toast.makeText(context,Strings.t("未能保存不再提示设置，请重试。","Could not save this preference. Please try again."),Toast.LENGTH_LONG).show()
            return false
        }
        if(notices.value==notice) dismiss()
        return true
    }
    fun dismiss() {
        val old=dialog; dialog=null; old?.dismiss(); notices.value=null
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }
    fun stop() { dismiss(); incidents.clear() }
}
