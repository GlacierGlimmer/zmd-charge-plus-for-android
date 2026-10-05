package com.glacierglimmer.endfieldchargeplus.overlay

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData

/**
 * Owns the overlay window: creation, layout parameters, position, visibility and touch behaviour.
 *
 * The window is created with the smallest possible size around the HUD so it never covers the whole
 * screen and never blocks touches intended for the application underneath.
 */
interface OverlayController {

    /** True when the overlay window is currently attached. */
    fun isShowing(): Boolean

    /** True when `Settings.canDrawOverlays` allows attaching. */
    fun canShow(): Boolean

    fun show()

    fun hide()

    /** Pushes new content to the renderer, optionally playing the reveal transition. */
    fun update(data: HudRenderData, animate: Boolean = true)

    /** Applies configuration changes (scale, opacity, click-through, anchor, offsets). */
    fun applyConfig(config: AppConfig)

    /** Called when the configuration or user drags the HUD to a new position. */
    fun persistPosition(x: Int, y: Int)

    /** Releases the window; called when the service stops. */
    fun release()

    /** Notifies the controller that the device rotated so it can restore the right anchor. */
    fun onConfigurationChanged()

    /** True while a reveal/hide transition is running. */
    fun isTransitioning(): Boolean
}
