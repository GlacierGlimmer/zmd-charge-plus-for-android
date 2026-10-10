package com.glacierglimmer.endfieldchargeplus.overlay

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating ECP / Endfield-style HUD, drawn with Canvas (no Compose, no XML layout).
 *
 * The visual language is a direct port of `Views/HudWindow.axaml` (plus `Styles/HudTheme.axaml`),
 * see `docs/audit/01-windows-core.md` §5.1-§5.3:
 *
 * | Part | Desktop source | Android port |
 * | --- | --- | --- |
 * | Pill | 560 × 60, radius 30, `#312F30`, clipped | same design units, scaled by density × `setScaledDensity` |
 * | Ripples | 160 filled / 220 ring 5 / 280 ring 3.5, `#656363` | same, clipped to the pill |
 * | Icon plate | 32 circle `#E9E7E4` + 18 glyph `#141313` | same |
 * | Square form | 18 rounded rect r4.5 + 12 glyph | same |
 * | Tagline | 9, letter spacing 2, 40 % white | same |
 * | Title | 26 bold, letter spacing 2 | same |
 * | Primary / secondary | 26 / 14, secondary at 55 % | same |
 * | Right / suffix | 22 / 13, suffix at 55 % | same |
 * | Badge | 46 dark disc `#262425`, accent arc stroke 4.5, round caps, start −90° | same, sweep `clamp(360·fraction, 0.5, 359.5)` |
 * | Right icon | `battery`/`laptop` draw the hand-made laptop badge, every other name its glyph | same |
 *
 * Two renderings are Android-specific and documented in place: the hairline accent ring behind the
 * progress arc (the "hairline accent geometry" of the port) and the typography fallback
 * (`sans-serif-medium` / `sans-serif`), because no Inter font asset ships with this repository.
 *
 * The view is usable both inside the overlay window and inside a Compose settings preview
 * (`AndroidView`), which is why it only depends on [HudRenderData] and
 * [HudAnimationState] — never on the window, the service or the metric layer.
 */
class OverlayHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), HudAnimationTarget {

    private var deviceDensity: Float = resources.displayMetrics.density

    internal var onGeometryChanged: (() -> Unit)? = null

    /** `GlobalScale × HudScale` (or the settings preview's own scale), applied on top of density. */
    private var renderScale: Float = 1f

    private var data: HudRenderData = HudRenderData()

    private var animationState: HudAnimationState = HudAnimationStates.persistentFinalState()

    private var accentColor: Int = Color.parseColor(DEFAULT_ACCENT)

    private var hasRendered: Boolean = false

    private var progressDisplayed: Double = 0.0

    private var progressAnimator: ValueAnimator? = null

    private val iconCache = HashMap<String, Path>()

    private val pillPath = Path()
    private val scratchRect = RectF()
    private val scratchRect2 = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val numericTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val titleTypeface: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    init {
        // Keep rounded clipping and opacity consistent across vendor GPU implementations.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
        textPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * Replaces the content of the HUD.
     *
     * The progress ring follows the desktop smoothing rule: the first value snaps, a change smaller
     * than 0.0005 snaps, and every other change eases from the currently displayed fraction over
     * 320 ms with `1 − (1 − t)³`.
     */
    fun setRenderData(data: HudRenderData) {
        this.data = data
        accentColor = parseColorOrDefault(data.accentColor, DEFAULT_ACCENT)
        setProgressTarget(data.progress, animate = hasRendered && isShown)
        invalidate()
    }

    /** The content currently rendered; used by the settings preview and diagnostics. */
    fun renderData(): HudRenderData = data

    /**
     * Sets the scale applied on top of the device density.
     *
     * One desktop design unit becomes `density × scale` pixels, so
     * `setScaledDensity(globalScale × AndroidSettings.hudScale)` reproduces the desktop
     * `GlobalScale` transform through the view's own density.
     */
    fun setScaledDensity(scale: Float, density: Float = resources.displayMetrics.density) {
        val clamped = scale.coerceIn(MIN_RENDER_SCALE, MAX_RENDER_SCALE)
        if (clamped == renderScale && density == deviceDensity) return
        renderScale = clamped
        deviceDensity = density
        iconCache.clear()
        requestLayout()
        invalidate()
    }

    /** The scale currently applied; exposed so the controller can keep the window in sync. */
    fun scaledDensity(): Float = renderScale

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setScaledDensity(renderScale)
        onGeometryChanged?.invoke()
    }

    /** Applies one sampled animation frame. Called on the main thread by [HudAnimator]. */
    override fun applyAnimationState(state: HudAnimationState) {
        animationState = state
        invalidate()
    }

    /** The last animation frame applied; exposed for tests and the settings preview. */
    fun animationState(): HudAnimationState = animationState

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unit = deviceDensity * renderScale
        val desiredWidth = (DESIGN_VIEW_WIDTH * unit).roundToInt()
        val desiredHeight = (DESIGN_VIEW_HEIGHT * unit).roundToInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDetachedFromWindow() {
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    /**
     * The HUD is draggable rather than clickable, but it still has to honour the accessibility
     * contract for a custom view that installs a touch listener: a tap that does not move the window
     * is reported as a click (see DefaultOverlayController) so assistive technology sees a consistent
     * interaction model. There is no click listener attached by default, so this is a no-op today.
     */
    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = deviceDensity * renderScale
        if (unit <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f

        val scaleHost = animationState[HudNode.SCALE_HOST]
        canvas.save()
        canvas.scale(scaleHost.scaleX, scaleHost.scaleY, centerX, centerY)

        val pill = animationState[HudNode.PILL]
        val pillWidth = DESIGN_PILL_WIDTH * unit
        val pillHeight = pill.height * unit
        val pillLeft = centerX - pillWidth / 2f
        val pillTop = centerY - pillHeight / 2f
        val pillRect = scratchRect
        pillRect.set(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)
        val radius = pill.cornerRadius * unit
        val pillOpacity = pill.opacity.coerceIn(0f, 1f)

        if (pillOpacity > ALPHA_EPSILON) {
            canvas.save()
            pillPath.reset()
            pillPath.addRoundRect(pillRect, radius, radius, Path.Direction.CW)
            canvas.clipPath(pillPath)
            canvas.save()
            canvas.scale(pill.scaleX, pill.scaleY, pillRect.centerX(), pillRect.centerY())
            paint.style = Paint.Style.FILL
            paint.color = PILL_COLOR
            paint.alpha = alphaOf(pillOpacity)
            canvas.drawRoundRect(pillRect, radius, radius, paint)
            drawRipples(canvas, pillRect, unit, pillOpacity)
            canvas.restore()
            canvas.restore()
        }

        drawTitleHost(canvas, centerX, centerY, unit)
        drawNumHost(canvas, pillRect, centerY, unit)
        drawBoltIcon(canvas, centerX, centerY, unit)

        canvas.restore()
    }

    /** Pill background + the three expanding rings, clipped to the pill like the desktop Border. */
    private fun drawRipples(canvas: Canvas, pillRect: RectF, unit: Float, pillOpacity: Float) {
        val host = animationState[HudNode.RIPPLE_HOST]
        val rise = animationState[HudNode.RIPPLE_RISE]
        val originX = pillRect.centerX() + host.translateX * unit
        val originY = pillRect.centerY() + rise.translateY * unit
        drawRipple(canvas, HudNode.RIPPLE_INNER, DESIGN_RIPPLE_INNER, originX, originY, unit, pillOpacity, 0f)
        drawRipple(canvas, HudNode.RIPPLE_MID, DESIGN_RIPPLE_MID, originX, originY, unit, pillOpacity, 5f)
        drawRipple(canvas, HudNode.RIPPLE_OUTER, DESIGN_RIPPLE_OUTER, originX, originY, unit, pillOpacity, 3.5f)
    }

    private fun drawRipple(
        canvas: Canvas,
        node: HudNode,
        diameterDesign: Float,
        originX: Float,
        originY: Float,
        unit: Float,
        pillOpacity: Float,
        strokeDesign: Float,
    ) {
        val state = animationState[node]
        val alpha = alphaOf(state.opacity * pillOpacity)
        if (alpha <= 0) return
        val radius = diameterDesign * unit / 2f * state.scaleX
        if (radius <= 0f) return
        paint.color = RIPPLE_COLOR
        paint.alpha = alpha
        if (strokeDesign <= 0f) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(originX, originY, radius, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeDesign * unit * state.scaleX
            canvas.drawCircle(originX, originY, radius, paint)
        }
    }

    /** Tagline + title as one vertically centred block (`TitleHost`). */
    private fun drawTitleHost(canvas: Canvas, centerX: Float, centerY: Float, unit: Float) {
        val host = animationState[HudNode.TITLE_HOST]
        val alpha = alphaOf(host.opacity)
        if (alpha <= 0) return

        textPaint.textAlign = Paint.Align.CENTER

        val taglineSize = DESIGN_TAGLINE * unit
        textPaint.typeface = numericTypeface
        textPaint.textSize = taglineSize
        textPaint.letterSpacing = DESIGN_TAGLINE_SPACING / DESIGN_TAGLINE
        val tagMetrics = textPaint.fontMetrics
        val tagLineHeight = tagMetrics.descent - tagMetrics.ascent

        val titleSize = DESIGN_TITLE * unit
        textPaint.typeface = titleTypeface
        textPaint.textSize = titleSize
        textPaint.letterSpacing = DESIGN_TITLE_SPACING / DESIGN_TITLE
        val titleMetrics = textPaint.fontMetrics
        val titleLineHeight = titleMetrics.descent - titleMetrics.ascent

        val spacing = 2f * unit
        val blockTop = centerY - (tagLineHeight + spacing + titleLineHeight) / 2f

        textPaint.color = TEXT_PRIMARY
        textPaint.alpha = alphaOf(host.opacity * TAGLINE_OPACITY)
        textPaint.typeface = numericTypeface
        textPaint.textSize = taglineSize
        textPaint.letterSpacing = DESIGN_TAGLINE_SPACING / DESIGN_TAGLINE
        canvas.drawText(data.tagline, centerX, blockTop - tagMetrics.ascent, textPaint)

        textPaint.alpha = alpha
        textPaint.typeface = titleTypeface
        textPaint.textSize = titleSize
        textPaint.letterSpacing = DESIGN_TITLE_SPACING / DESIGN_TITLE
        canvas.drawText(
            data.title,
            centerX,
            blockTop + tagLineHeight + spacing - titleMetrics.ascent,
            textPaint,
        )
    }

    /** Left numbers, right numbers and the badge (`NumHost`, the 32|18|14|Auto|*|Auto|14|Auto|5 grid). */
    private fun drawNumHost(canvas: Canvas, pillRect: RectF, centerY: Float, unit: Float) {
        val host = animationState[HudNode.NUM_HOST]
        val alpha = alphaOf(host.opacity)
        if (alpha <= 0) return
        val shift = host.translateX * unit
        val pillLeft = pillRect.left + shift
        val pillRight = pillRect.right + shift

        val badgeSize = DESIGN_BADGE * unit
        val badgeLeft = pillRight - (DESIGN_BADGE_GAP + DESIGN_BADGE) * unit
        val badgeCenterX = badgeLeft + badgeSize / 2f

        // Left group: primary + secondary, both vertically centred on the pill.
        val primarySize = DESIGN_PRIMARY * unit
        val secondarySize = DESIGN_SECONDARY * unit
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = numericTypeface

        textPaint.textSize = primarySize
        textPaint.letterSpacing = 0f
        textPaint.color = TEXT_PRIMARY
        textPaint.alpha = alpha
        val primaryBaseline = centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        val primaryLeft = pillLeft + DESIGN_LEFT_INSET * unit
        canvas.drawText(data.primaryText, primaryLeft, primaryBaseline, textPaint)
        val primaryWidth = textPaint.measureText(data.primaryText)

        textPaint.textSize = secondarySize
        textPaint.alpha = alphaOf(host.opacity * SECONDARY_OPACITY)
        canvas.drawText(
            data.secondaryText,
            primaryLeft + primaryWidth + 2f * unit,
            centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint,
        )

        // Right group: value + suffix, right-aligned against the badge.
        val rightSize = DESIGN_RIGHT * unit
        val suffixSize = DESIGN_SUFFIX * unit
        textPaint.textAlign = Paint.Align.RIGHT

        textPaint.textSize = suffixSize
        textPaint.letterSpacing = 0f
        textPaint.alpha = alphaOf(host.opacity * SECONDARY_OPACITY)
        val suffixRight = badgeLeft - DESIGN_RIGHT_GAP * unit
        canvas.drawText(
            data.rightSuffix,
            suffixRight,
            centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint,
        )
        val suffixWidth = textPaint.measureText(data.rightSuffix)

        textPaint.textSize = rightSize
        textPaint.alpha = alpha
        canvas.drawText(
            data.rightText,
            suffixRight - suffixWidth - 1f * unit,
            centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f,
            textPaint,
        )

        drawBadge(canvas, badgeCenterX, centerY, badgeSize, unit, host.opacity)
    }

    /** Dark disc, hairline accent track, progress arc and the right icon. */
    private fun drawBadge(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        badgeSize: Float,
        unit: Float,
        hostOpacity: Float,
    ) {
        val radius = badgeSize / 2f
        paint.style = Paint.Style.FILL
        paint.color = BADGE_COLOR
        paint.alpha = alphaOf(hostOpacity)
        canvas.drawCircle(centerX, centerY, radius, paint)

        val arcInset = DESIGN_BADGE_STROKE * unit / 2f
        scratchRect2.set(
            centerX - radius + arcInset,
            centerY - radius + arcInset,
            centerX + radius - arcInset,
            centerY + radius - arcInset,
        )

        // Android-specific hairline accent geometry: the progress track, drawn faintly so the ring
        // reads as a gauge even at 0 % (the desktop leaves the track invisible).
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = accentColor
        paint.alpha = alphaOf(hostOpacity * ACCENT_TRACK_OPACITY)
        canvas.drawCircle(centerX, centerY, radius - arcInset, paint)

        val sweep = (360.0 * progressDisplayed.coerceIn(0.0, 1.0))
            .coerceIn(MIN_ARC_SWEEP, MAX_ARC_SWEEP)
            .toFloat()
        paint.strokeWidth = DESIGN_BADGE_STROKE * unit
        paint.strokeCap = Paint.Cap.ROUND
        paint.alpha = alphaOf(hostOpacity)
        canvas.drawArc(scratchRect2, -90f, sweep, false, paint)

        val iconName = data.rightIcon
        if (iconName.equals("battery", ignoreCase = true) || iconName.equals("laptop", ignoreCase = true)) {
            drawLaptopBadge(canvas, centerX, centerY, unit, hostOpacity)
        } else {
            drawGlyph(
                canvas,
                iconName,
                DESIGN_BADGE_GLYPH * unit,
                centerX,
                centerY,
                accentColor,
                alphaOf(hostOpacity),
            )
        }
    }

    /**
     * The hand-drawn "laptop" badge the desktop shows for the `battery`/`laptop` right icon
     * (`HudWindow.axaml:184-198`): 17 × 11.5 screen with a 2-unit accent border, a 24 × 3 base and
     * the 9 × 3 electrode above the disc.
     */
    private fun drawLaptopBadge(canvas: Canvas, centerX: Float, centerY: Float, unit: Float, opacity: Float) {
        val alpha = alphaOf(opacity)
        paint.color = accentColor
        paint.alpha = alpha

        val screenWidth = DESIGN_LAPTOP_SCREEN_WIDTH * unit
        val screenHeight = DESIGN_LAPTOP_SCREEN_HEIGHT * unit
        val containerHeight = DESIGN_LAPTOP_HEIGHT * unit
        val containerTop = centerY - containerHeight / 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * unit
        scratchRect2.set(
            centerX - screenWidth / 2f,
            containerTop,
            centerX + screenWidth / 2f,
            containerTop + screenHeight,
        )
        canvas.drawRoundRect(scratchRect2, 1.5f * unit, 1.5f * unit, paint)

        val baseWidth = DESIGN_LAPTOP_WIDTH * unit
        val baseHeight = DESIGN_LAPTOP_BASE_HEIGHT * unit
        paint.style = Paint.Style.FILL
        scratchRect2.set(
            centerX - baseWidth / 2f,
            containerTop + containerHeight - baseHeight,
            centerX + baseWidth / 2f,
            containerTop + containerHeight,
        )
        canvas.drawRoundRect(scratchRect2, 1.5f * unit, 1.5f * unit, paint)

        val electrodeWidth = DESIGN_ELECTRODE_WIDTH * unit
        val electrodeHeight = DESIGN_ELECTRODE_HEIGHT * unit
        val electrodeCenterY = centerY - DESIGN_ELECTRODE_OFFSET * unit
        scratchRect2.set(
            centerX - electrodeWidth / 2f,
            electrodeCenterY - electrodeHeight / 2f,
            centerX + electrodeWidth / 2f,
            electrodeCenterY + electrodeHeight / 2f,
        )
        canvas.drawRoundRect(scratchRect2, unit, unit, paint)
    }

    /** The travelling icon plate with its two cross-fading forms (`CircleForm` / `SquareForm`). */
    private fun drawBoltIcon(canvas: Canvas, centerX: Float, centerY: Float, unit: Float) {
        val icon = animationState[HudNode.BOLT_ICON]
        val nodeAlpha = alphaOf(icon.opacity)
        if (nodeAlpha <= 0) return
        val iconCenterX = centerX + icon.translateX * unit

        canvas.save()
        canvas.scale(icon.scaleX, icon.scaleY, iconCenterX, centerY)

        val circleAlpha = alphaOf(animationState[HudNode.CIRCLE_FORM].opacity * icon.opacity)
        if (circleAlpha > 0) {
            paint.style = Paint.Style.FILL
            paint.color = ICON_PLATE_COLOR
            paint.alpha = circleAlpha
            canvas.drawCircle(iconCenterX, centerY, DESIGN_ICON_PLATE * unit / 2f, paint)
            drawGlyph(
                canvas,
                data.leftIcon,
                DESIGN_ICON_GLYPH * unit,
                iconCenterX,
                centerY,
                ICON_DARK_COLOR,
                circleAlpha,
            )
        }

        val squareAlpha = alphaOf(animationState[HudNode.SQUARE_FORM].opacity * icon.opacity)
        if (squareAlpha > 0) {
            val plate = DESIGN_SQUARE_PLATE * unit
            scratchRect2.set(
                iconCenterX - plate / 2f,
                centerY - plate / 2f,
                iconCenterX + plate / 2f,
                centerY + plate / 2f,
            )
            paint.style = Paint.Style.FILL
            paint.color = ICON_PLATE_COLOR
            paint.alpha = squareAlpha
            canvas.drawRoundRect(scratchRect2, 4.5f * unit, 4.5f * unit, paint)
            drawGlyph(
                canvas,
                data.leftIcon,
                DESIGN_SQUARE_GLYPH * unit,
                iconCenterX,
                centerY,
                ICON_DARK_COLOR,
                squareAlpha,
            )
        }

        canvas.restore()
    }

    private fun drawGlyph(
        canvas: Canvas,
        name: String,
        sizePx: Float,
        centerX: Float,
        centerY: Float,
        color: Int,
        alpha: Int,
    ) {
        if (alpha <= 0 || sizePx <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = alpha
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.drawPath(iconPath(name, sizePx), paint)
        canvas.restore()
    }

    private fun iconPath(name: String, sizePx: Float): Path {
        val key = name.lowercase() + '@' + sizePx.roundToInt()
        if (iconCache.size > MAX_CACHED_ICONS) iconCache.clear()
        return iconCache.getOrPut(key) { HudIconCatalog.path(name, sizePx) }
    }

    /**
     * The desktop retarget rule (`SetProgressTarget`, `HudWindow.axaml.cs:369-423`): the first
     * value snaps, a change smaller than 0.0005 snaps, otherwise the displayed value eases to the
     * new target over 320 ms with `1 − (1 − t)³`.
     */
    private fun setProgressTarget(target: Double, animate: Boolean) {
        val clamped = target.coerceIn(0.0, 1.0)
        val previous = progressDisplayed
        progressAnimator?.cancel()
        progressAnimator = null
        if (!animate || !hasRendered || abs(clamped - previous) <= PROGRESS_EPSILON) {
            progressDisplayed = clamped
            hasRendered = true
            return
        }
        hasRendered = true
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = PROGRESS_TRANSITION_MS
        animator.interpolator = CubicEaseOut
        animator.addUpdateListener { animation ->
            progressDisplayed = previous + (clamped - previous) * animation.animatedFraction
            invalidate()
        }
        progressAnimator = animator
        animator.start()
    }

    private fun alphaOf(fraction: Float): Int {
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun parseColorOrDefault(value: String, fallback: String): Int = try {
        Color.parseColor(value.trim())
    } catch (_: IllegalArgumentException) {
        Color.parseColor(fallback)
    }

    /** `1 − (1 − t)³`, the desktop progress easing. */
    private object CubicEaseOut : TimeInterpolator {
        override fun getInterpolation(input: Float): Float {
            val inverse = 1f - input
            return 1f - inverse * inverse * inverse
        }
    }

    companion object {
        /** Width of the visible pill in desktop design units. */
        const val DESIGN_PILL_WIDTH = 560f

        /** Height of the settled pill in desktop design units. */
        const val DESIGN_PILL_HEIGHT = 60f

        /** Measured width: the pill itself; the ripples are clipped inside it. */
        const val DESIGN_VIEW_WIDTH = 560f

        /** Measured height: the expanded pill (`PillHeight` peaks at 90) is never clipped. */
        const val DESIGN_VIEW_HEIGHT = 90f

        const val DEFAULT_ACCENT = "#C6CA4C"

        private const val DESIGN_ICON_PLATE = 32f
        private const val DESIGN_ICON_GLYPH = 18f
        private const val DESIGN_SQUARE_PLATE = 18f
        private const val DESIGN_SQUARE_GLYPH = 12f
        private const val DESIGN_BADGE = 46f
        private const val DESIGN_BADGE_GAP = 5f

        /** Desktop `NumHost` column 6: the gap between the right group and the badge. */
        private const val DESIGN_RIGHT_GAP = 14f
        private const val DESIGN_BADGE_STROKE = 4.5f
        private const val DESIGN_BADGE_GLYPH = 21f
        private const val DESIGN_LEFT_INSET = 64f
        private const val DESIGN_RIPPLE_INNER = 160f
        private const val DESIGN_RIPPLE_MID = 220f
        private const val DESIGN_RIPPLE_OUTER = 280f
        private const val DESIGN_TAGLINE = 9f
        private const val DESIGN_TITLE = 26f
        private const val DESIGN_PRIMARY = 26f
        private const val DESIGN_SECONDARY = 14f
        private const val DESIGN_RIGHT = 22f
        private const val DESIGN_SUFFIX = 13f
        private const val DESIGN_TAGLINE_SPACING = 2f
        private const val DESIGN_TITLE_SPACING = 2f
        private const val DESIGN_LAPTOP_WIDTH = 24f
        private const val DESIGN_LAPTOP_HEIGHT = 17f
        private const val DESIGN_LAPTOP_SCREEN_WIDTH = 17f
        private const val DESIGN_LAPTOP_SCREEN_HEIGHT = 11.5f
        private const val DESIGN_LAPTOP_BASE_HEIGHT = 3f
        private const val DESIGN_ELECTRODE_WIDTH = 9f
        private const val DESIGN_ELECTRODE_HEIGHT = 3f
        private const val DESIGN_ELECTRODE_OFFSET = 14f

        private const val TAGLINE_OPACITY = 0.40f
        private const val SECONDARY_OPACITY = 0.55f
        private const val ACCENT_TRACK_OPACITY = 0.18f
        private const val MIN_ARC_SWEEP = 0.5
        private const val MAX_ARC_SWEEP = 359.5
        private const val ALPHA_EPSILON = 0.001f
        private const val PROGRESS_EPSILON = 0.0005
        private const val PROGRESS_TRANSITION_MS = 320L
        private const val MIN_RENDER_SCALE = 0.1f
        private const val MAX_RENDER_SCALE = 4f
        private const val MAX_CACHED_ICONS = 64

        private val PILL_COLOR = Color.parseColor("#312F30")
        private val ICON_PLATE_COLOR = Color.parseColor("#E9E7E4")
        private val ICON_DARK_COLOR = Color.parseColor("#141313")
        private val BADGE_COLOR = Color.parseColor("#262425")
        private val RIPPLE_COLOR = Color.parseColor("#656363")
        private val TEXT_PRIMARY = Color.WHITE

        /** The visible pill width in pixels for a density and render scale. */
        fun hudWidthPx(density: Float, scale: Float): Int =
            (DESIGN_PILL_WIDTH * density * scale).roundToInt()

        /** The visible pill height in pixels for a density and render scale. */
        fun hudHeightPx(density: Float, scale: Float): Int =
            (DESIGN_PILL_HEIGHT * density * scale).roundToInt()
    }
}
