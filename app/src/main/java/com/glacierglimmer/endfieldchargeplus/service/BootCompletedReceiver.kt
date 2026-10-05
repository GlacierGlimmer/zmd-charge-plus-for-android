package com.glacierglimmer.endfieldchargeplus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts the HUD after a reboot or an app update — but **only** when the user asked for it.
 *
 * Product rule (all ECP platforms): the HUD is never started behind the user's back. Both switches
 * must be on:
 *  * `AndroidSettings.startOnBoot`, which the user explicitly enables, and
 *  * the HUD master switch `AppConfig.hudEnabled`.
 *
 * Reading DataStore needs suspension, so the receiver uses `goAsync()` and finishes the pending
 * result in a `finally`, which keeps the broadcast alive without a wake lock or any keep-alive
 * trick.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val container = EcpContainer.of(appContext)
                val config = runCatching { container.configRepository.load() }
                    .getOrElse { container.configRepository.config.value }
                if (config.android.startOnBoot && config.hudEnabled) {
                    AppLog.i(TAG, "Boot start requested by the user settings")
                    HudServiceController.start(appContext)
                } else {
                    AppLog.d(
                        TAG,
                        "Boot start skipped (startOnBoot=${config.android.startOnBoot}, " +
                            "hudEnabled=${config.hudEnabled})",
                    )
                }
            } catch (throwable: Throwable) {
                AppLog.w(TAG, "Boot start failed", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
