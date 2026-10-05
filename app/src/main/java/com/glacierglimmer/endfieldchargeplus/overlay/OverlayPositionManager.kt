package com.glacierglimmer.endfieldchargeplus.overlay

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import kotlin.math.max

/**
 * The insets of one display that the overlay must stay clear of.
 *
 * @param left cutout-safe inset on the left edge.
 * @param top status-bar (and, when `avoidCutout` is on, cutout) inset on the top edge.
 * @param right cutout-safe inset on the right edge.
 * @param bottom navigation-bar inset on the bottom edge; only applied when `avoidCutout` is on, so
 *   a user who disabled cutout avoidance can still park the HUD under the navigation bar.
 */
data class HudInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

/** The display rectangle the overlay lives in, plus the insets that form its safe area. */
data class HudDisplayFrame(
    val widthPx: Int,
    val heightPx: Int,
    val insets: HudInsets = HudInsets(),
) {
    val safeLeft: Int get() = insets.left
    val safeTop: Int get() = insets.top
    val safeRight: Int get() = widthPx - insets.right
    val safeBottom: Int get() = heightPx - insets.bottom
    val safeWidth: Int get() = max(0, safeRight - safeLeft)
    val safeHeight: Int get() = max(0, safeBottom - safeTop)

    companion object {
        /** Used when the platform cannot report a display; keeps the HUD at the origin. */
        val Unknown = HudDisplayFrame(0, 0)
    }
}

/** A resolved window position for `Gravity.TOP or Gravity.START`, in screen pixels. */
data class HudWindowPosition(val x: Int, val y: Int)

/**
 * Pure position resolution: AppConfig + display frame + HUD size → window coordinates.
 *
 * Split out of [OverlayPositionManager] so every anchor and both orientations are covered by plain
 * JVM tests (no Android framework needed).
 *
 * Ported from `HudWindow.PositionHud` (`HudWindow.axaml.cs:559-608`), with these Android mappings:
 *  * the desktop `WorkingArea` becomes the **safe area** (display minus status bar/cutout/nav bar);
 *  * `HudCustomX/Y` are offsets from the safe-area origin, exactly like the desktop's
 *    working-area origin;
 *  * the per-orientation free position (`AndroidSettings.overlayXPortrait/YPortrait` /
 *    `overlayXLandscape/YLandscape`) is a dragged position for the **current** orientation and,
 *    once **both** of its coordinates are set (`>= 0`), takes precedence over the preset/custom
 *    modes; `-1` means "this orientation was never dragged" (a drag always writes both);
 *  * `const margin = 16` is kept for every preset anchor;
 *  * the result is clamped into the safe area, so the HUD can never sit off-screen or under the
 *    status bar, whatever the user configured.
 */
object HudPositionMath {

    /** The desktop edge margin for preset anchors. */
    const val MARGIN = 16

    /** Scale clamp for `GlobalScale × HudScale`; the desktop UI range is 0.4 … 1.4. */
    const val MIN_SCALE = 0.2f
    const val MAX_SCALE = 2.0f

    /** The HUD may occupy at most this fraction of the safe width before it is scaled down. */
    const val MAX_WIDTH_FRACTION = 0.94f

    /** Resolves the window position for the current orientation. */
    fun resolve(
        config: AppConfig,
        frame: HudDisplayFrame,
        landscape: Boolean,
        hudWidthPx: Int,
        hudHeightPx: Int,
    ): HudWindowPosition {
        val freeX = if (landscape) config.android.overlayXLandscape else config.android.overlayXPortrait
        val freeY = if (landscape) config.android.overlayYLandscape else config.android.overlayYPortrait

        val offsetX: Int
        val offsetY: Int
        if (freeX >= 0 && freeY >= 0) {
            offsetX = freeX
            offsetY = freeY
        } else if (config.positionModeEnum == HudPositionMode.CUSTOM_COORDINATES) {
            offsetX = config.hudCustomX
            offsetY = config.hudCustomY
        } else {
            val position = config.positionEnum
            offsetX = when (position) {
                HudPosition.TOP_LEFT, HudPosition.CENTER_LEFT, HudPosition.BOTTOM_LEFT -> MARGIN
                HudPosition.TOP_RIGHT, HudPosition.CENTER_RIGHT, HudPosition.BOTTOM_RIGHT ->
                    frame.safeWidth - hudWidthPx - MARGIN
                else -> (frame.safeWidth - hudWidthPx) / 2
            } + config.hudOffsetX
            offsetY = when (position) {
                HudPosition.TOP_LEFT, HudPosition.TOP_CENTER, HudPosition.TOP_RIGHT -> MARGIN
                HudPosition.BOTTOM_LEFT, HudPosition.BOTTOM_CENTER, HudPosition.BOTTOM_RIGHT ->
                    frame.safeHeight - hudHeightPx - MARGIN
                else -> (frame.safeHeight - hudHeightPx) / 2
            } + config.hudOffsetY
        }

        return clamp(
            frame = frame,
            x = frame.safeLeft + offsetX,
            y = frame.safeTop + offsetY,
            hudWidthPx = hudWidthPx,
            hudHeightPx = hudHeightPx,
        )
    }

    /**
     * Clamps an absolute window position into the safe area.
     *
     * Used both by [resolve] and after a drag, so a HUD dragged past an edge always snaps back
     * inside the safe area on `ACTION_UP`.
     */
    fun clamp(
        frame: HudDisplayFrame,
        x: Int,
        y: Int,
        hudWidthPx: Int,
        hudHeightPx: Int,
    ): HudWindowPosition {
        val maxX = max(0, frame.safeWidth - hudWidthPx)
        val maxY = max(0, frame.safeHeight - hudHeightPx)
        return HudWindowPosition(
            x = x.coerceIn(frame.safeLeft, frame.safeLeft + maxX),
            y = y.coerceIn(frame.safeTop, frame.safeTop + maxY),
        )
    }

    /**
     * Clamps the configured `GlobalScale × HudScale` so the HUD always fits the screen width.
     *
     * The desktop pill is 560 design units wide; on a phone, honouring the user scale unchecked
     * would produce a window wider than the display. The clamp only ever shrinks the HUD and is the
     * single Android-specific adjustment of the port.
     */
    fun fitScale(
        rawScale: Double,
        frame: HudDisplayFrame,
        density: Float,
        designWidth: Float = OverlayHudView.DESIGN_PILL_WIDTH,
    ): Float {
        var scale = rawScale.coerceIn(MIN_SCALE.toDouble(), MAX_SCALE.toDouble()).toFloat()
        val maxWidth = frame.safeWidth * MAX_WIDTH_FRACTION
        if (maxWidth > 0f && density > 0f) {
            val width = designWidth * density * scale
            if (width > maxWidth) scale *= maxWidth / width
        }
        return scale
    }
}

/**
 * Reads the display size, insets and orientation for the overlay window.
 *
 * Insets are taken from the attached overlay view's `rootWindowInsets` whenever the caller can
 * provide them, because that is the only source that is correct on every supported API level
 * (the window's own insets, including the display cutout). `WindowManager.currentWindowMetrics`
 * (API 30+) is the fallback and also provides the display size.
 */
class OverlayPositionManager(context: Context) {

    private val appContext: Context = context.applicationContext

    /** True when the device is currently in landscape. */
    fun isLandscape(): Boolean =
        appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** The display density used to convert the HUD design units into pixels. */
    fun density(): Float = appContext.resources.displayMetrics.density

    /** The display frame, including the safe-area insets for [config]. */
    fun displayFrame(config: AppConfig, windowInsets: WindowInsets? = null): HudDisplayFrame {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
            ?: return HudDisplayFrame.Unknown
        val size = displaySize(windowManager)
        if (size[0] <= 0 || size[1] <= 0) return HudDisplayFrame.Unknown
        return HudDisplayFrame(size[0], size[1], insetsFor(config, windowInsets))
    }

    /**
     * Resolves the window position with the insets currently known to the overlay window.
     *
     * @param windowInsets the attached view's `rootWindowInsets`, or null before the window is
     *   attached.
     */
    fun resolve(
        config: AppConfig,
        hudWidthPx: Int,
        hudHeightPx: Int,
        windowInsets: WindowInsets? = null,
    ): HudWindowPosition = HudPositionMath.resolve(
        config = config,
        frame = displayFrame(config, windowInsets),
        landscape = isLandscape(),
        hudWidthPx = hudWidthPx,
        hudHeightPx = hudHeightPx,
    )

    private fun displaySize(windowManager: WindowManager): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return intArrayOf(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    private fun insetsFor(config: AppConfig, windowInsets: WindowInsets?): HudInsets {
        val avoidCutout = config.android.avoidCutout
        if (windowInsets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            var left = 0
            var top = bars.top
            var right = 0
            val bottom = if (avoidCutout) bars.bottom else 0
            if (avoidCutout) {
                val cutout = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                left = max(left, cutout.left)
                top = max(top, cutout.top)
                right = max(right, cutout.right)
            }
            return HudInsets(left = left, top = top, right = right, bottom = bottom)
        }
        if (windowInsets != null) {
            @Suppress("DEPRECATION")
            val bars = windowInsets.systemWindowInsets
            @Suppress("DEPRECATION")
            var top = bars.top
            var left = 0
            var right = 0
            val bottom = if (avoidCutout) bars.bottom else 0
            if (avoidCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = windowInsets.displayCutout
                if (cutout != null) {
                    left = max(left, cutout.safeInsetLeft)
                    top = max(top, cutout.safeInsetTop)
                    right = max(right, cutout.safeInsetRight)
                }
            }
            return HudInsets(left = left, top = top, right = right, bottom = bottom)
        }
        // The window is not attached yet: fall back to the platform's own bar dimensions.
        return HudInsets(
            top = dimensionPx("status_bar_height"),
            bottom = if (avoidCutout) dimensionPx("navigation_bar_height") else 0,
        )
    }

    private fun dimensionPx(name: String): Int {
        val resources = appContext.resources
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}
