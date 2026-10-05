package com.glacierglimmer.endfieldchargeplus

import android.app.Application
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog

/**
 * Application entry point.
 *
 * Responsibilities are deliberately tiny: install diagnostics and warm up the object graph. The HUD
 * itself never lives here — it belongs to [com.glacierglimmer.endfieldchargeplus.service.HudForegroundService].
 */
class EcpApplication : Application() {

    val container: EcpContainer by lazy { EcpContainer.of(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.install(this) { container.configRepository.config.value.android.verboseLogging }
        installCrashLogger()
        container.warmUp()
        AppLog.i(TAG, "Endfield Charge Plus for Android started")
    }

    /** Records unexpected crashes in the in-app log so the diagnostics page can show them. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "EcpApplication"
    }
}
