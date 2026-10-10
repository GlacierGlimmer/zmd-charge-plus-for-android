package com.glacierglimmer.endfieldchargeplus.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog

/** Reused for the overlay's resources, display metrics, view and WindowManager. */
internal fun overlayWindowContext(appContext: Context): Context = try {
    val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    if (display == null) appContext else {
        val displayContext = appContext.createDisplayContext(display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        else displayContext
    }
} catch (error: Exception) {
    AppLog.w("OverlayContext", "Could not create a display-bound overlay context", error)
    appContext
}
