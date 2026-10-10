package com.glacierglimmer.endfieldchargeplus.overlay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/** Global inset sizes plus current visibility, never the small window's clipped inset distances. */
internal object HudSafeArea {
    fun resolve(status: HudInsets, navigation: HudInsets, cutout: HudInsets,
                statusVisible: Boolean, navigationVisible: Boolean, avoidCutout: Boolean): HudInsets {
        val top = if (statusVisible) status.top else 0
        if (!avoidCutout) return HudInsets(top = top)
        val bars = if (navigationVisible) navigation else HudInsets()
        return HudInsets(max(bars.left, cutout.left), max(max(top, bars.top), cutout.top),
            max(bars.right, cutout.right), max(bars.bottom, cutout.bottom))
    }
}

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

/** A resolved window position for `Gravity.TOP or Gravity.LEFT`, in screen pixels. */
data class HudWindowPosition(val x: Int, val y: Int)

/** One immutable display snapshot drives both the view size and the window position. */
data class HudWindowGeometry(val scale: Float, val density: Float, val width: Int, val height: Int, val position: HudWindowPosition)

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

    fun geometry(config: AppConfig, frame: HudDisplayFrame, density: Float): HudWindowGeometry {
        val scale = fitScale(config.globalScale * config.android.hudScale, frame, density)
        val width = (OverlayHudView.DESIGN_VIEW_WIDTH * density * scale).roundToInt()
        val height = (OverlayHudView.DESIGN_VIEW_HEIGHT * density * scale).roundToInt()
        return HudWindowGeometry(scale, density, width, height,
            resolve(config, frame, frame.widthPx > frame.heightPx, width, height))
    }

    /** The desktop edge margin for preset anchors. */
    const val MARGIN = 16

    /** Scale clamp for `GlobalScale × HudScale`; the desktop UI range is 0.4 … 1.4. */
    const val MIN_SCALE = 0.2f
    const val MAX_SCALE = 2.0f

    /** The HUD may occupy at most this fraction of the safe width before it is scaled down. */
    const val MAX_WIDTH_FRACTION = 0.94f
    /** Landscape phones need a compact HUD; cap width against available screen height. */
    const val MAX_LANDSCAPE_WIDTH_TO_HEIGHT = 0.70f

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
        if (config.android.useDraggedPosition && freeX >= 0 && freeY >= 0) {
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
        var scale = (rawScale.takeIf { it.isFinite() } ?: 1.0).coerceIn(MIN_SCALE.toDouble(), MAX_SCALE.toDouble()).toFloat()
        // Portrait can use the phone width. Landscape leaves more of the shorter game viewport free.
        val maxWidth = if (frame.widthPx > frame.heightPx)
            min(frame.safeWidth.toFloat(), frame.heightPx * MAX_LANDSCAPE_WIDTH_TO_HEIGHT)
        else min(frame.safeWidth, min(frame.widthPx, frame.heightPx)) * MAX_WIDTH_FRACTION
        if (maxWidth > 0f && density > 0f) {
            val width = designWidth * density * scale
            if (width > maxWidth) scale *= maxWidth / width
        }
        val maxHeight = frame.safeHeight * MAX_WIDTH_FRACTION
        val height = OverlayHudView.DESIGN_VIEW_HEIGHT * density * scale
        if (maxHeight > 0f && height > maxHeight) scale *= maxHeight / height
        return scale
    }
}

/** Full-display metrics: attached HUD insets are window-relative and cannot position that window. */
class OverlayPositionManager(private val windowContext: Context) {
    fun density(): Float = windowContext.resources.displayMetrics.density

    fun displayFrame(config: AppConfig, observedInsets: WindowInsets? = null): HudDisplayFrame {
        val manager = windowContext.getSystemService(WindowManager::class.java) ?: return HudDisplayFrame.Unknown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // MATCH_PARENT metrics and insets remain independent of the small HUD's current origin.
            // The overlay covers the display, independently of Activity multi-window/compat bounds
            // or its own small attached window. Current-window bounds can represent those containers.
            val metrics = manager.maximumWindowMetrics
            val bounds = metrics.bounds
            val status = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            val navigation = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            // Visibility describes the screen regardless of overlap with this window (API contract).
            // A fullscreen game hiding its bars must not retain the portrait status-bar reservation.
            val visibility = observedInsets ?: metrics.windowInsets
            val safe = HudSafeArea.resolve(
                HudInsets(status.left, status.top, status.right, status.bottom),
                HudInsets(navigation.left, navigation.top, navigation.right, navigation.bottom),
                HudInsets(cutout.left, cutout.top, cutout.right, cutout.bottom),
                visibility.isVisible(WindowInsets.Type.statusBars()),
                visibility.isVisible(WindowInsets.Type.navigationBars()), config.android.avoidCutout)
            return HudDisplayFrame(bounds.width(), bounds.height(), safe)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = manager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val bottom = if (config.android.avoidCutout) dimensionPx("navigation_bar_height") else 0
        var safe = HudInsets(top = dimensionPx("status_bar_height"), bottom = bottom)
        if (config.android.avoidCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            display.cutout?.let { cutout -> safe = HudInsets(
                left = cutout.safeInsetLeft, top = max(safe.top, cutout.safeInsetTop),
                right = cutout.safeInsetRight, bottom = max(safe.bottom, cutout.safeInsetBottom)) }
        }
        return HudDisplayFrame(metrics.widthPixels, metrics.heightPixels, safe)
    }

    fun resolve(config: AppConfig, hudWidthPx: Int, hudHeightPx: Int): HudWindowPosition {
        val frame = displayFrame(config)
        return HudPositionMath.resolve(config, frame, frame.widthPx > frame.heightPx, hudWidthPx, hudHeightPx)
    }

    private fun dimensionPx(name: String): Int {
        val resources = windowContext.resources
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}
