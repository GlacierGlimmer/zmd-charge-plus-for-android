package com.glacierglimmer.endfieldchargeplus.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.service.HudRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Visibility callbacks for the HUD service; the settings UI reads [HudRuntimeState] instead. */
interface OverlayStateListener {

    /** Called whenever the overlay window is attached or detached. */
    fun onOverlayVisibilityChanged(showing: Boolean)
}

/**
 * The production [OverlayController]: one `TYPE_APPLICATION_OVERLAY` window that is exactly as
 * large as the HUD and never steals focus.
 *
 * Window contract enforced here (hard product rules):
 *  * `WRAP_CONTENT` in **both** dimensions — the window is the HUD, never a full-screen transparent
 *    surface;
 *  * `PixelFormat.TRANSLUCENT` so the pill's translucency and the ripples composite correctly;
 *  * `FLAG_NOT_FOCUSABLE` always, so the HUD can never take input focus or break the keyboard;
 *  * `FLAG_NOT_TOUCHABLE` when `AndroidSettings.clickThrough` is on, plus `FLAG_NOT_TOUCH_MODAL`
 *    when it is off, so touches outside the HUD still reach the application underneath;
 *  * `FLAG_LAYOUT_IN_SCREEN` so the anchor coordinates are screen coordinates, and
 *    `FLAG_LAYOUT_NO_LIMITS` **only while a drag is in progress**, so the finger can leave the safe
 *    area and the window still follows it until `ACTION_UP` clamps it back;
 *  * `alpha` from `AppConfig.hudOpacity` (clamped to 0.1 … 1.0 like the desktop persistence) and
 *    scale from `GlobalScale × AndroidSettings.hudScale`, fed into the view's density.
 *
 * The controller is constructed with the application context only (`DefaultOverlayController(context)`,
 * the signature the DI container pins). The collaborators it needs are optional constructor
 * parameters defaulting to the process container, which also means a test can inject its own
 * `ConfigRepository`/scope.
 */
class DefaultOverlayController @JvmOverloads constructor(
    context: Context,
    private val configRepository: ConfigRepository = EcpContainer.of(context).configRepository,
    private val scope: CoroutineScope = EcpContainer.of(context).applicationScope,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : OverlayController {

    private val appContext: Context = context.applicationContext

    private val windowManager: WindowManager? = appContext.getSystemService(WindowManager::class.java)

    private val positions = OverlayPositionManager(appContext)

    private var view: OverlayHudView? = null

    private var animator: HudAnimator? = null

    private var layoutParams: WindowManager.LayoutParams? = null

    private var config: AppConfig = AppConfig()

    private var currentData: HudRenderData? = null

    private var showing = false

    private var dragging = false

    private var dragStartX = 0

    private var dragStartY = 0

    private var dragTouchX = 0f

    private var dragTouchY = 0f

    private var stateListener: OverlayStateListener? = null

    /** Registers the service's visibility callback. */
    fun setStateListener(listener: OverlayStateListener?) {
        stateListener = listener
    }

    override fun isShowing(): Boolean = showing

    override fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    override fun isTransitioning(): Boolean = animator?.isRunning == true

    override fun show() {
        if (showing) return
        if (!canShow()) {
            reportPermissionLoss()
            return
        }
        val manager = windowManager
        if (manager == null) {
            reportError("WindowManager is unavailable on this context")
            return
        }
        val hudView = view ?: createView()
        val params = buildLayoutParams(hudView)
        layoutParams = params
        try {
            manager.addView(hudView, params)
        } catch (throwable: Throwable) {
            AppLog.e(TAG, "Could not attach the HUD overlay window", throwable)
            detachWindow()
            reportError(throwable.message ?: throwable.javaClass.simpleName)
            return
        }
        showing = true
        clearError()
        // Re-apply now that the window is attached: the insets are known only from this point on.
        applyConfig(config)
        notifyListener()
        val data = currentData
        val options = HudAnimationOptions.from(config)
        if (data == null) {
            hudView.applyAnimationState(HudAnimationStates.persistentFinalState())
        } else {
            animator?.playReveal(options, data.simpleAnimation, transient = isTransient()) {
                onRevealFinished()
            }
        }
    }

    override fun hide() {
        if (!showing) return
        val running = animator
        if (running == null || view?.isAttachedToWindow != true) {
            detachWindow()
            return
        }
        running.playHide { detachWindow() }
    }

    /**
     * Pushes new content.
     *
     * `animate = true` means "this is a new scheme": the content switch is the first-class
     * "隐去 → 内容切换 → 唤出" sequence, never an in-place morph. `animate = false` is an ordinary
     * live refresh, applied in place while the progress ring smooths itself.
     */
    override fun update(data: HudRenderData, animate: Boolean) {
        currentData = data
        if (!showing) {
            if (!canShow()) {
                reportPermissionLoss()
                return
            }
            // First content after a permission grant or a mode switch: attach and reveal it.
            show()
            return
        }
        val hudView = view ?: return
        if (animate) {
            animator?.swapContent(
                options = HudAnimationOptions.from(config),
                simple = data.simpleAnimation,
                transient = isTransient(),
                swap = { hudView.setRenderData(data) },
                onComplete = { onRevealFinished() },
            )
        } else {
            hudView.setRenderData(data)
        }
    }

    override fun applyConfig(config: AppConfig) {
        this.config = config
        val hudView = view ?: return
        val params = layoutParams ?: return
        val manager = windowManager ?: return

        val scale = effectiveScale()
        hudView.setScaledDensity(scale)
        params.alpha = config.hudOpacity.coerceIn(MIN_OPACITY, MAX_OPACITY).toFloat()
        params.flags = windowFlags(isDragging = dragging)
        applyCutoutMode(params)
        params.gravity = Gravity.TOP or Gravity.START
        val size = hudSizePx(scale)
        val position = positions.resolve(config, size[0], size[1], hudView.rootWindowInsets)
        params.x = position.x
        params.y = position.y
        runCatching { manager.updateViewLayout(hudView, params) }
            .onFailure { AppLog.w(TAG, "Could not update the HUD window layout: ${it.message}") }
    }

    /**
     * Persists a dragged position for the **current** orientation only.
     *
     * Values are stored relative to the safe-area origin, exactly like `HudCustomX/Y`, so they stay
     * meaningful when the status bar or cutout size changes.
     */
    override fun persistPosition(x: Int, y: Int) {
        val frame = positions.displayFrame(config, view?.rootWindowInsets)
        val relativeX = x - frame.safeLeft
        val relativeY = y - frame.safeTop
        val landscape = positions.isLandscape()
        config = if (landscape) {
            config.copy(
                android = config.android.copy(
                    overlayXLandscape = relativeX,
                    overlayYLandscape = relativeY,
                ),
            )
        } else {
            config.copy(
                android = config.android.copy(
                    overlayXPortrait = relativeX,
                    overlayYPortrait = relativeY,
                ),
            )
        }
        scope.launch {
            runCatching {
                configRepository.update { current ->
                    val android = if (landscape) {
                        current.android.copy(
                            overlayXLandscape = relativeX,
                            overlayYLandscape = relativeY,
                        )
                    } else {
                        current.android.copy(
                            overlayXPortrait = relativeX,
                            overlayYPortrait = relativeY,
                        )
                    }
                    current.copy(android = android)
                }
            }.onFailure {
                AppLog.w(TAG, "Could not persist the dragged HUD position: ${it.message}")
            }
        }
    }

    override fun release() {
        animator?.cancel()
        animator = null
        detachWindow()
    }

    /** Rotation: the position manager reads the new orientation, so the right anchor is restored. */
    override fun onConfigurationChanged() {
        if (view == null) return
        applyConfig(config)
    }

    private fun createView(): OverlayHudView {
        val created = OverlayHudView(appContext)
        created.setScaledDensity(effectiveScale())
        created.setRenderData(currentData ?: HudRenderData())
        created.applyAnimationState(HudAnimationStates.initial())
        attachTouchListener(created)
        view = created
        animator = HudAnimator(created)
        return created
    }

    private fun buildLayoutParams(hudView: OverlayHudView): WindowManager.LayoutParams {
        val scale = effectiveScale()
        val size = hudSizePx(scale)
        val position = positions.resolve(config, size[0], size[1], hudView.rootWindowInsets)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(isDragging = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
            alpha = config.hudOpacity.coerceIn(MIN_OPACITY, MAX_OPACITY).toFloat()
            applyCutoutMode(this)
        }
    }

    /**
     * Wires dragging.
     *
     * The HUD keeps `FLAG_NOT_FOCUSABLE` while the user drags it; only its position changes. The
     * new position is persisted on `ACTION_UP` (and on `ACTION_CANCEL`, so a cancelled gesture does
     * not leave an unsaved position).
     */
    private fun attachTouchListener(hudView: OverlayHudView) {
        hudView.setOnTouchListener { touched, event ->
            if (config.android.clickThrough) return@setOnTouchListener false
            val params = layoutParams ?: return@setOnTouchListener false
            val manager = windowManager ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartX = params.x
                    dragStartY = params.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    params.flags = windowFlags(isDragging = true)
                    runCatching { manager.updateViewLayout(touched, params) }
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = dragStartX + (event.rawX - dragTouchX).roundToInt()
                    params.y = dragStartY + (event.rawY - dragTouchY).roundToInt()
                    runCatching { manager.updateViewLayout(touched, params) }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val wasTap = params.x == dragStartX && params.y == dragStartY
                    params.flags = windowFlags(isDragging = false)
                    val scale = (touched as? OverlayHudView)?.scaledDensity() ?: effectiveScale()
                    clampIntoPlace(touched, params, scale)
                    persistPosition(params.x, params.y)
                    // Accessibility: a tap that did not move the HUD is reported as a click so
                    // assistive technology and the platform see a consistent interaction.
                    if (wasTap) touched.performClick()
                }
            }
            true
        }
    }

    private fun clampIntoPlace(
        hudView: View,
        params: WindowManager.LayoutParams,
        scale: Float,
    ) {
        val frame = positions.displayFrame(config, hudView.rootWindowInsets)
        val size = hudSizePx(scale)
        val clamped = HudPositionMath.clamp(frame, params.x, params.y, size[0], size[1])
        params.x = clamped.x
        params.y = clamped.y
        runCatching { windowManager?.updateViewLayout(hudView, params) }
    }

    /**
     * The window flags. `FLAG_NOT_FOCUSABLE` is unconditional (the HUD must never take focus);
     * `FLAG_NOT_TOUCH_MODAL` keeps outside touches, including the keyboard, working while the HUD
     * is interactive; `FLAG_LAYOUT_NO_LIMITS` is added only during a drag.
     */
    private fun windowFlags(isDragging: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        flags = if (config.android.clickThrough) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        if (isDragging) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return flags
    }

    private fun applyCutoutMode(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        params.layoutInDisplayCutoutMode = if (config.android.avoidCutout) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** `GlobalScale × AndroidSettings.hudScale`, fitted so the HUD always fits the screen width. */
    private fun effectiveScale(): Float = HudPositionMath.fitScale(
        rawScale = config.globalScale * config.android.hudScale,
        frame = positions.displayFrame(config, view?.rootWindowInsets),
        density = positions.density(),
    )

    private fun hudSizePx(scale: Float): IntArray {
        val density = positions.density()
        return intArrayOf(
            OverlayHudView.hudWidthPx(density, scale),
            OverlayHudView.hudHeightPx(density, scale),
        )
    }

    private fun isTransient(): Boolean = !config.android.alwaysVisible

    private fun onRevealFinished() {
        if (isTransient()) {
            // The transient timeline ended at scale 0; the window has nothing left to show.
            detachWindow()
        }
    }

    private fun detachWindow() {
        animator?.cancel()
        animator = null
        val hudView = view
        view = null
        layoutParams = null
        dragging = false
        if (hudView != null) {
            runCatching { windowManager?.removeViewImmediate(hudView) }
                .onFailure { AppLog.w(TAG, "Could not remove the HUD overlay window: ${it.message}") }
        }
        if (showing) {
            showing = false
            notifyListener()
        }
    }

    private fun notifyListener() {
        val listener = stateListener ?: return
        mainHandler.post { listener.onOverlayVisibilityChanged(showing) }
    }

    /** Permission loss is a normal, recoverable state: hide the HUD and report it. */
    private fun reportPermissionLoss() {
        AppLog.w(TAG, "Overlay permission is missing; the HUD cannot be shown")
        if (showing) detachWindow()
        HudRuntimeState.update {
            it.copy(overlayShowing = false, lastError = ERROR_OVERLAY_PERMISSION)
        }
    }

    private fun reportError(message: String) {
        if (showing) detachWindow()
        HudRuntimeState.update { it.copy(overlayShowing = false, lastError = message) }
    }

    private fun clearError() {
        HudRuntimeState.update { if (it.lastError == null) it else it.copy(lastError = null) }
    }

    companion object {
        private const val TAG = "OverlayController"

        /** Opacity clamp of the desktop persistence (`SettingsManager.cs:151`). */
        private const val MIN_OPACITY = 0.1
        private const val MAX_OPACITY = 1.0

        /**
         * Human-readable diagnostic text stored in `HudRuntimeStatus.lastError` when
         * `Settings.canDrawOverlays` is false. Kept as plain text (not a resource id) so the
         * diagnostics page and the settings banner can display it verbatim.
         */
        const val ERROR_OVERLAY_PERMISSION =
            "Overlay permission (SYSTEM_ALERT_WINDOW) is not granted"
    }
}
