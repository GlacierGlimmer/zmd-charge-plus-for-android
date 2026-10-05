package com.glacierglimmer.endfieldchargeplus.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import kotlin.math.roundToLong

/**
 * The ECP animation language, ported from `Animations/HudAnimations.cs` (quoted in
 * `docs/audit/01-windows-core.md` §5 "HUD layout and animation").
 *
 * The port is deliberately split in two:
 *  * a **pure timeline model** (cues, easings, keyframes, sampling) that has no Android dependency
 *    and is covered by JVM tests, and
 *  * a thin **driver** ([HudAnimator]) that plays a timeline with a `ValueAnimator` (which is
 *    itself driven by the `Choreographer` frame clock) and pushes one [HudAnimationState] per
 *    frame into a [HudAnimationTarget].
 *
 * Faithfulness notes:
 *  * Avalonia applies a keyframe's `KeySpline` to the segment *ending* at that keyframe; a `null`
 *    spline is linear. [HudTrack.valueAt] reproduces exactly that.
 *  * Avalonia uses `FillMode.Forward`: after the last keyframe of a channel the last value is held.
 *  * Cue values are fractions of the timeline duration, and `MapCue`/`MapCueSimple` compress or
 *    stretch the intro so the *intro keeps its absolute duration* when the user changes
 *    `DisplayDurationSeconds` (default mapping at 6 s is the identity).
 *  * Timelines keep the full user duration (`clamp(seconds, 3, 10)`) even when the visible retract
 *    happens earlier (`closeMs`): the desktop also hides the window only when the whole timeline
 *    completes. `holdMs`/`closeMs` expose the mapped cue times so callers and tests can assert the
 *    port.
 */
data class HudAnimationOptions(
    val durationSeconds: Double = AppConfig.DEFAULT_DISPLAY_DURATION_SECONDS,
    val bounceStrength: Double = AppConfig.DEFAULT_BOUNCE_STRENGTH,
    val rippleIntensity: Double = AppConfig.DEFAULT_RIPPLE_INTENSITY,
    val rippleSpread: Double = AppConfig.DEFAULT_RIPPLE_SPREAD,
) {

    /** The desktop clamp (`HudAnimations.cs:21-24`): the timeline duration is 3 s … 10 s. */
    val clampedDurationSeconds: Double
        get() = durationSeconds.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)

    /** The desktop clamp for `BounceStrength`: 0 … 0.5. */
    val clampedBounceStrength: Double get() = bounceStrength.coerceIn(0.0, 0.5)

    /** The desktop clamp for `RippleIntensity`: 0 … 2. */
    val clampedRippleIntensity: Double get() = rippleIntensity.coerceIn(0.0, 2.0)

    /** The desktop clamp for `RippleSpread`: 0.5 … 1.5. */
    val clampedRippleSpread: Double get() = rippleSpread.coerceIn(0.5, 1.5)

    companion object {
        const val MIN_DURATION_SECONDS = 3.0
        const val MAX_DURATION_SECONDS = 10.0

        /** Reads the four animation values from the persisted configuration, applying the clamps. */
        fun from(config: AppConfig): HudAnimationOptions = HudAnimationOptions(
            durationSeconds = config.displayDurationSeconds,
            bounceStrength = config.bounceStrength,
            rippleIntensity = config.rippleIntensity,
            rippleSpread = config.rippleSpread,
        )
    }
}

/**
 * Every cue and every constant of the desktop timeline (`HudAnimations.cs:31-51`, `:206-210`).
 *
 * [mapFull] and [mapSimple] are the literal `MapCue` / `MapCueSimple` formulas.
 */
object HudCues {

    // ---- Full timeline (BaselineSeconds = 6.0) ----
    const val BASELINE_SECONDS = 6.0
    const val INTRO_END_CUE = 0.42
    const val T_START = 0.04
    const val T_APPEAR = 0.07
    const val T_PILL_OUT = 0.09
    const val T_BOLT_POP = 0.10
    const val T_EXPAND = 0.12
    const val T_MOVE = 0.20
    const val T_TITLE = 0.25
    const val T_HOLD_B = 0.30
    const val T_CONTRACT = 0.36
    const val T_HOLD_C = 0.86
    const val T_CLOSE = 0.89
    const val T_NUM_IN = 0.38
    const val T_NUM_READY = 0.42

    const val PILL_RADIUS_A = 30.0
    const val PILL_RADIUS_B = 18.0
    const val PILL_HEIGHT_A = 60.0
    const val PILL_HEIGHT_B = 90.0
    const val ICON_OFFSET_B = -179.0
    const val ICON_OFFSET_C = -245.0

    /** `RippleRise` starts one design unit below its resting place. */
    const val RIPPLE_RISE_OFFSET = 16.0

    // ---- Simple timeline (SimpleBaselineSeconds = 5.0) ----
    const val SIMPLE_BASELINE_SECONDS = 5.0
    const val SIMPLE_INTRO_END_CUE = 0.08
    const val T_SIMPLE_APPEAR = 0.05
    const val T_SIMPLE_FADE = 0.08
    const val T_SIMPLE_HOLD = 0.75
    const val T_SIMPLE_CLOSE = 0.80

    /** The fixed 180 ms retract used for persistent/close/settings-switch paths. */
    const val HIDE_DURATION_MS = 180L

    /**
     * `MapCue` (`HudAnimations.cs:61-68`): `d = clamp(DurationSeconds, 3, 10)`,
     * `introFrac = 0.42 · 6 / d`; cues at or below 0.42 compress into the intro, later cues are
     * stretched across the rest of the timeline.
     */
    fun mapFull(options: HudAnimationOptions, cue: Double): Double {
        val duration = options.clampedDurationSeconds
        val introFraction = INTRO_END_CUE * BASELINE_SECONDS / duration
        return if (cue <= INTRO_END_CUE) {
            cue / INTRO_END_CUE * introFraction
        } else {
            introFraction + (cue - INTRO_END_CUE) / (1 - INTRO_END_CUE) * (1 - introFraction)
        }
    }

    /**
     * `MapCueSimple` (`HudAnimations.cs:212-219`): identical formula with the 5 s simple baseline
     * and the 0.08 intro end, which is what makes the simple mode show the final HUD almost
     * immediately.
     */
    fun mapSimple(options: HudAnimationOptions, cue: Double): Double {
        val duration = options.clampedDurationSeconds
        val introFraction = SIMPLE_INTRO_END_CUE * SIMPLE_BASELINE_SECONDS / duration
        return if (cue <= SIMPLE_INTRO_END_CUE) {
            cue / SIMPLE_INTRO_END_CUE * introFraction
        } else {
            introFraction + (cue - SIMPLE_INTRO_END_CUE) / (1 - SIMPLE_INTRO_END_CUE) * (1 - introFraction)
        }
    }
}

/**
 * A cubic-bezier easing, equivalent to Avalonia's `KeySpline` (and to CSS `cubic-bezier`).
 *
 * The four desktop splines are `KS_In = cubic(0.42,0,1,1)`, `KS_Out = cubic(0,0,0.58,1)`,
 * `KS_InOut = cubic(0.42,0,0.58,1)`, `KS_Smooth = cubic(0.65,0,0.35,1)`, plus
 * `BackOut(o) = cubic(0.175, 0.885, 0.32, 1 + BounceStrength)`.
 */
class HudEasing(
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double,
) {

    /** Evaluates `y` for the curve parameter `x` (the argument is treated as a time fraction). */
    fun ease(input: Double): Double {
        val x = input.coerceIn(0.0, 1.0)
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        var t = x
        repeat(NEWTON_ITERATIONS) {
            val slope = curveDerivative(t, x1, x2)
            if (slope == 0.0) return@repeat
            val error = curve(t, x1, x2) - x
            t -= error / slope
        }
        if (t < 0.0 || t > 1.0) {
            // Newton wandered outside the curve: fall back to a bisection on the same parameter.
            var low = 0.0
            var high = 1.0
            t = x
            repeat(BISECTION_ITERATIONS) {
                val current = curve(t, x1, x2)
                if (current < x) low = t else high = t
                t = (low + high) / 2.0
            }
        }
        return curve(t, y1, y2)
    }

    private fun curve(t: Double, a1: Double, a2: Double): Double {
        val inverse = 1.0 - t
        return 3.0 * inverse * inverse * t * a1 + 3.0 * inverse * t * t * a2 + t * t * t
    }

    private fun curveDerivative(t: Double, a1: Double, a2: Double): Double {
        val inverse = 1.0 - t
        return 3.0 * a1 * inverse * (1.0 - 3.0 * t) + 3.0 * a2 * t * (2.0 - 3.0 * t) + 3.0 * t * t
    }

    companion object {
        private const val NEWTON_ITERATIONS = 8
        private const val BISECTION_ITERATIONS = 24

        /** Used where the desktop leaves `KeySpline` unset. */
        val LINEAR = HudEasing(0.0, 0.0, 1.0, 1.0)
        val IN = HudEasing(0.42, 0.0, 1.0, 1.0)
        val OUT = HudEasing(0.0, 0.0, 0.58, 1.0)
        val IN_OUT = HudEasing(0.42, 0.0, 0.58, 1.0)
        val SMOOTH = HudEasing(0.65, 0.0, 0.35, 1.0)

        /** `BackOut(o) = cubic(0.175, 0.885, 0.32, 1 + BounceStrength)`. */
        fun backOut(bounceStrength: Double): HudEasing =
            HudEasing(0.175, 0.885, 0.32, 1.0 + bounceStrength.coerceIn(0.0, 0.5))
    }
}

/** One animated channel of one HUD node. Mirrors the Avalonia properties the desktop animates. */
enum class HudChannel {
    OPACITY,
    TRANSLATE_X,
    TRANSLATE_Y,
    SCALE_X,
    SCALE_Y,
    CORNER_RADIUS,
    HEIGHT,
}

/** One addressable piece of the HUD visual tree (the desktop animates exactly these). */
enum class HudNode {
    /** Entrance/exit scale layer (`ScaleHost`), wraps the whole HUD. */
    SCALE_HOST,

    /** The pill border (`Pill`): opacity, scale, corner radius and height. */
    PILL,

    /** Ripple container (`RippleHost`): horizontal travel and the temporarily grown height. */
    RIPPLE_HOST,

    /** Vertical rise shared by the three ripple hosts. */
    RIPPLE_RISE,
    RIPPLE_INNER,
    RIPPLE_MID,
    RIPPLE_OUTER,

    /** Icon plate (`BoltIcon`), which also travels left into the C state. */
    BOLT_ICON,

    /** Circle icon form, visible during the entrance. */
    CIRCLE_FORM,

    /** Square icon form, visible from the B → C transition onwards. */
    SQUARE_FORM,

    /** Tagline + title (`TitleHost`). */
    TITLE_HOST,

    /** Left numbers, right numbers and the badge (`NumHost`). */
    NUM_HOST,
}

/** One resolved node state. Values are in desktop design units (px of the 560 × 60 pill). */
data class HudNodeState(
    val opacity: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val cornerRadius: Float = HudCues.PILL_RADIUS_A.toFloat(),
    val height: Float = HudCues.PILL_HEIGHT_A.toFloat(),
) {
    /** Returns a copy with [channel] replaced by [value]. */
    fun with(channel: HudChannel, value: Double): HudNodeState = when (channel) {
        HudChannel.OPACITY -> copy(opacity = value.toFloat())
        HudChannel.TRANSLATE_X -> copy(translateX = value.toFloat())
        HudChannel.TRANSLATE_Y -> copy(translateY = value.toFloat())
        HudChannel.SCALE_X -> copy(scaleX = value.toFloat())
        HudChannel.SCALE_Y -> copy(scaleY = value.toFloat())
        HudChannel.CORNER_RADIUS -> copy(cornerRadius = value.toFloat())
        HudChannel.HEIGHT -> copy(height = value.toFloat())
    }
}

/** Every node state at one instant. Nodes without an animation keep their defaults. */
data class HudAnimationState(val nodes: Map<HudNode, HudNodeState>) {

    operator fun get(node: HudNode): HudNodeState = nodes[node] ?: DEFAULT

    /**
     * Returns this state with every node of [overrides] replaced.
     *
     * A timeline only animates the channels it declares, so the driver seeds each sampled frame with
     * the baseline the desktop sets before playing (`ResetToInitial()` for the full timeline,
     * `SetSimpleCState()` for the simple one) — see [HudTimeline.stateAt]'s `baseline` parameter.
     * Without that, nodes the timeline does not touch would fall back to their neutral defaults and
     * the simple mode would show the circle form, the title and the ripples.
     */
    fun overlay(overrides: HudAnimationState): HudAnimationState =
        HudAnimationState(nodes + overrides.nodes)

    companion object {
        val DEFAULT = HudNodeState()

        val Empty = HudAnimationState(emptyMap())
    }
}

/** One keyframe: the values a node's channels have at the (already mapped) [cue]. */
data class HudKeyframe(
    val cue: Double,
    val easing: HudEasing?,
    val values: Map<HudChannel, Double>,
)

/** A single channel track: keyframes sorted by cue, interpolated with the target keyframe's easing. */
class HudTrack internal constructor(
    private val cues: DoubleArray,
    private val values: DoubleArray,
    private val easings: Array<HudEasing?>,
) {

    /** Value at the timeline fraction [progress], holding the first/last value outside the range. */
    fun valueAt(progress: Double): Double {
        if (progress <= cues.first()) return values.first()
        if (progress >= cues.last()) return values.last()
        var index = 1
        while (index < cues.size && cues[index] < progress) index++
        val previousCue = cues[index - 1]
        val nextCue = cues[index]
        val span = nextCue - previousCue
        val local = if (span <= 0.0) 1.0 else (progress - previousCue) / span
        val eased = (easings[index] ?: HudEasing.LINEAR).ease(local)
        return values[index - 1] + (values[index] - values[index - 1]) * eased
    }

    /** Number of keyframes in this track, for tests and diagnostics. */
    val size: Int get() = cues.size
}

/** Which timeline a [HudTimeline] instance is. */
enum class HudTimelineKind { FULL, SIMPLE, HIDE }

/**
 * An immutable, sampled timeline.
 *
 * @param durationMs the playback duration (`clamp(DisplayDurationSeconds, 3, 10)` in ms; 180 ms for
 *   the fixed retract).
 * @param holdMs the mapped cue at which the persistent C state is reached.
 * @param closeMs the mapped cue at which the transient retract reaches scale 0 (equals `holdMs`
 *   when the timeline has no retract).
 */
class HudTimeline internal constructor(
    val kind: HudTimelineKind,
    val durationMs: Long,
    val holdMs: Long,
    val closeMs: Long,
    private val tracks: Map<HudNode, Map<HudChannel, HudTrack>>,
) {

    /** The timeline fraction at [elapsedMs], clamped into `0..1` (Avalonia `FillMode.Forward`). */
    fun progressAt(elapsedMs: Long): Double =
        if (durationMs <= 0L) 1.0 else (elapsedMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)

    /**
     * State of one node at [progress] (0 … 1).
     *
     * @param baseline the node state the timeline layers onto; channels the timeline does not
     *   animate keep their baseline value. Defaults to the neutral node state.
     */
    fun nodeStateAt(
        node: HudNode,
        progress: Double,
        baseline: HudNodeState = HudAnimationState.DEFAULT,
    ): HudNodeState {
        val channels = tracks[node] ?: return baseline
        var state = baseline
        for ((channel, track) in channels) {
            state = state.with(channel, track.valueAt(progress))
        }
        return state
    }

    /**
     * Full state at the timeline fraction [progress].
     *
     * Nodes without a track are **absent** from the result, so a caller that needs a complete frame
     * merges the result over its baseline with [HudAnimationState.overlay].
     *
     * @param baseline seeded into every node the timeline does animate, so channels the timeline
     *   leaves alone are not reset to their defaults.
     */
    fun stateAt(
        progress: Double,
        baseline: HudAnimationState = HudAnimationState.Empty,
    ): HudAnimationState {
        val clamped = progress.coerceIn(0.0, 1.0)
        val states = HashMap<HudNode, HudNodeState>(tracks.size)
        for (node in tracks.keys) {
            states[node] = nodeStateAt(node, clamped, baseline[node])
        }
        return HudAnimationState(states)
    }

    /** Full state at [elapsedMs] of playback. */
    fun stateAtMs(
        elapsedMs: Long,
        baseline: HudAnimationState = HudAnimationState.Empty,
    ): HudAnimationState = stateAt(progressAt(elapsedMs), baseline)
}

/**
 * The three canned node states that are not part of a timeline.
 *
 * Ported from `HudWindow.axaml.cs`: `ResetToInitial()` (`:468-500`), `SetSimpleCState()`
 * (`:502-528`) and `SetPersistentFinalState()` (`:530-557`).
 */
object HudAnimationStates {

    /**
     * `ResetToInitial()`: the document-order initial state, applied before every full reveal.
     * `CircleForm` is invisible because it fades in at `TAppear`; `SquareForm` is invisible until
     * the contraction.
     */
    fun initial(): HudAnimationState = HudAnimationState(
        mapOf(
            HudNode.SCALE_HOST to HudNodeState(scaleX = 1f, scaleY = 1f),
            HudNode.PILL to HudNodeState(
                opacity = 0f,
                scaleX = 0.6f,
                scaleY = 0.6f,
                cornerRadius = HudCues.PILL_RADIUS_A.toFloat(),
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_HOST to HudNodeState(
                translateX = 0f,
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_RISE to HudNodeState(translateY = HudCues.RIPPLE_RISE_OFFSET.toFloat()),
            HudNode.RIPPLE_INNER to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.RIPPLE_MID to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.RIPPLE_OUTER to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.BOLT_ICON to HudNodeState(
                opacity = 0f,
                scaleX = 0.4f,
                scaleY = 0.4f,
                translateX = 0f,
            ),
            HudNode.CIRCLE_FORM to HudNodeState(opacity = 0f),
            HudNode.SQUARE_FORM to HudNodeState(opacity = 0f),
            HudNode.TITLE_HOST to HudNodeState(opacity = 0f),
            HudNode.NUM_HOST to HudNodeState(opacity = 0f, translateX = 0f),
        ),
    )

    /**
     * `SetSimpleCState()`: the starting state of the simple timeline. The pill is invisible and
     * scaled to 0.6, the icon already sits at the final `X = -245` and the **square** form is the
     * visible one, so the simple mode only has to fade the pill and the numbers in.
     */
    fun simpleCState(): HudAnimationState = HudAnimationState(
        mapOf(
            HudNode.SCALE_HOST to HudNodeState(scaleX = 1f, scaleY = 1f),
            HudNode.PILL to HudNodeState(
                opacity = 0f,
                scaleX = 0.6f,
                scaleY = 0.6f,
                cornerRadius = HudCues.PILL_RADIUS_A.toFloat(),
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_HOST to HudNodeState(
                translateX = 0f,
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_RISE to HudNodeState(translateY = HudCues.RIPPLE_RISE_OFFSET.toFloat()),
            HudNode.RIPPLE_INNER to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.RIPPLE_MID to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.RIPPLE_OUTER to HudNodeState(opacity = 0f, scaleX = 0f, scaleY = 0f),
            HudNode.BOLT_ICON to HudNodeState(
                opacity = 0f,
                scaleX = 1f,
                scaleY = 1f,
                translateX = HudCues.ICON_OFFSET_C.toFloat(),
            ),
            HudNode.CIRCLE_FORM to HudNodeState(opacity = 0f),
            HudNode.SQUARE_FORM to HudNodeState(opacity = 1f),
            HudNode.TITLE_HOST to HudNodeState(opacity = 0f),
            HudNode.NUM_HOST to HudNodeState(opacity = 0f, translateX = 0f),
        ),
    )

    /**
     * `SetPersistentFinalState()`: the settled C state used by the always-visible mode and as the
     * starting point of the 180 ms retract. Note that it keeps the **square** icon form and the
     * pill fully opaque.
     */
    fun persistentFinalState(): HudAnimationState = HudAnimationState(
        mapOf(
            HudNode.SCALE_HOST to HudNodeState(scaleX = 1f, scaleY = 1f),
            HudNode.PILL to HudNodeState(
                opacity = 1f,
                scaleX = 1f,
                scaleY = 1f,
                cornerRadius = HudCues.PILL_RADIUS_A.toFloat(),
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_HOST to HudNodeState(
                translateX = HudCues.ICON_OFFSET_C.toFloat(),
                height = HudCues.PILL_HEIGHT_A.toFloat(),
            ),
            HudNode.RIPPLE_RISE to HudNodeState(translateY = 0f),
            HudNode.RIPPLE_INNER to HudNodeState(opacity = 0f),
            HudNode.RIPPLE_MID to HudNodeState(opacity = 0f),
            HudNode.RIPPLE_OUTER to HudNodeState(opacity = 0f),
            HudNode.BOLT_ICON to HudNodeState(
                opacity = 1f,
                scaleX = 1f,
                scaleY = 1f,
                translateX = HudCues.ICON_OFFSET_C.toFloat(),
            ),
            HudNode.CIRCLE_FORM to HudNodeState(opacity = 0f),
            HudNode.SQUARE_FORM to HudNodeState(opacity = 1f),
            HudNode.TITLE_HOST to HudNodeState(opacity = 0f),
            HudNode.NUM_HOST to HudNodeState(opacity = 1f, translateX = 0f),
        ),
    )

    /** The fully retracted state: the whole HUD is scaled to nothing. */
    fun closed(): HudAnimationState = HudAnimationState(
        mapOf(HudNode.SCALE_HOST to HudNodeState(scaleX = 0f, scaleY = 0f)),
    )
}

/** Builds the three timelines of the ECP animation language. */
object HudTimelines {

    private val OP = HudChannel.OPACITY
    private val TX = HudChannel.TRANSLATE_X
    private val TY = HudChannel.TRANSLATE_Y
    private val SX = HudChannel.SCALE_X
    private val SY = HudChannel.SCALE_Y
    private val CR = HudChannel.CORNER_RADIUS
    private val H = HudChannel.HEIGHT

    /**
     * The full timeline (`HudAnimations.cs:70-204`).
     *
     * Cue table (identical to the desktop, before `MapCue`):
     *  * `PillCorner`     0.00 r=30 → 0.07 r=30 → 0.12 r=18 → 0.30 r=18 → 0.36 r=30
     *  * `PillAppear`     0.00/0.04/0.07 opacity 0 scale 0.6 → 0.09 (BackOut) opacity 1 scale 1 → 0.86
     *  * `PillHeight`     0.00 h=60 → 0.07 h=60 → 0.12 (BackOut) h=90 → 0.30 h=90 → 0.36 h=60 → 0.86
     *  * `ScaleOut`       0.00 s=1 → 0.86 s=1 → 0.89 s=0
     *  * `BoltIcon`       0.00/0.04 opacity 0 scale 0.4 X=0 → 0.10 (BackOut) opacity 1 scale 1.12 →
     *                     0.12 scale 1 → 0.20 (Smooth) X=−179 → 0.30 X=−179 → 0.36 (Smooth) X=−245 → 0.86
     *  * `RippleHost`     X: 0 → 0.12 X=0 → 0.20 X=−179 → 0.30 X=−179 → 0.36 X=−245
     *  * `CircleForm`     opacity 0 @0.00 → 0 @0.04 → 1 @0.07 → 1 @0.30 → 0 @0.36
     *  * `SquareForm`     opacity 0 through 0.30 → 1 @0.36
     *  * `TitleHost`      0 → 0.20 0 → 0.25 1 → 0.30 1 → 0.36 0
     *  * `NumHost`        0 → 0.36 0 → 0.38 0 → 0.42 1 → 0.86 1
     *  * `RippleRise`     TY 16 → 0.12 16 → 0.14 16 → 0.30 0 → 0.36 0
     *  * `Ripple`         inner(1.5, 0.50), mid(2.0, 0.50), outer(2.5, 0.60): peak =
     *                     min(1, peakOp · RippleIntensity), target = endScale · RippleSpread;
     *                     opacity 0/scale 0 → 0.14 → 0.30 (peak, target) → 0.36 opacity 0
     *
     * @param includeScaleOut `false` for a persistent show: the desktop runs the same set minus
     *   `ScaleOut` and then applies [HudAnimationStates.persistentFinalState].
     */
    fun full(options: HudAnimationOptions, includeScaleOut: Boolean): HudTimeline {
        val durationMs = (options.clampedDurationSeconds * 1000.0).roundToLong()
        val backOut = HudEasing.backOut(options.clampedBounceStrength)
        val builder = TimelineBuilder(HudTimelineKind.FULL, durationMs)
        fun cue(value: Double): Double = HudCues.mapFull(options, value)

        builder.add(
            HudNode.PILL,
            frame(cue(0.00), null, OP to 0.0, SX to 0.6, SY to 0.6),
            frame(cue(HudCues.T_START), HudEasing.IN, OP to 0.0, SX to 0.6, SY to 0.6),
            frame(cue(HudCues.T_APPEAR), HudEasing.IN, OP to 0.0, SX to 0.6, SY to 0.6),
            frame(cue(HudCues.T_PILL_OUT), backOut, OP to 1.0, SX to 1.0, SY to 1.0),
            frame(cue(HudCues.T_HOLD_C), HudEasing.IN, OP to 1.0, SX to 1.0, SY to 1.0),
        )
        builder.add(
            HudNode.PILL,
            frame(cue(0.00), null, CR to HudCues.PILL_RADIUS_A),
            frame(cue(HudCues.T_APPEAR), HudEasing.IN, CR to HudCues.PILL_RADIUS_A),
            frame(cue(HudCues.T_EXPAND), HudEasing.IN_OUT, CR to HudCues.PILL_RADIUS_B),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, CR to HudCues.PILL_RADIUS_B),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, CR to HudCues.PILL_RADIUS_A),
        )
        val pillHeight = arrayOf(
            frame(cue(0.00), null, H to HudCues.PILL_HEIGHT_A),
            frame(cue(HudCues.T_APPEAR), HudEasing.IN, H to HudCues.PILL_HEIGHT_A),
            frame(cue(HudCues.T_EXPAND), backOut, H to HudCues.PILL_HEIGHT_B),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, H to HudCues.PILL_HEIGHT_B),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, H to HudCues.PILL_HEIGHT_A),
            frame(cue(HudCues.T_HOLD_C), HudEasing.IN, H to HudCues.PILL_HEIGHT_A),
        )
        builder.add(HudNode.PILL, *pillHeight)
        // The desktop binds PillHeight to the ripple host as well (`HudWindow.axaml.cs:127-128`).
        builder.add(HudNode.RIPPLE_HOST, *pillHeight)

        if (includeScaleOut) {
            builder.add(
                HudNode.SCALE_HOST,
                frame(cue(0.00), null, SX to 1.0, SY to 1.0),
                frame(cue(HudCues.T_HOLD_C), HudEasing.IN, SX to 1.0, SY to 1.0),
                frame(cue(HudCues.T_CLOSE), HudEasing.IN, SX to 0.0, SY to 0.0),
            )
        }

        builder.add(
            HudNode.BOLT_ICON,
            frame(cue(0.00), null, OP to 0.0, SX to 0.4, SY to 0.4, TX to 0.0),
            frame(cue(HudCues.T_START), HudEasing.IN, OP to 0.0, SX to 0.4, SY to 0.4, TX to 0.0),
            frame(cue(HudCues.T_BOLT_POP), backOut, OP to 1.0, SX to 1.12, SY to 1.12, TX to 0.0),
            frame(cue(HudCues.T_EXPAND), HudEasing.OUT, OP to 1.0, SX to 1.0, SY to 1.0, TX to 0.0),
            frame(
                cue(HudCues.T_MOVE), HudEasing.SMOOTH,
                OP to 1.0, SX to 1.0, SY to 1.0, TX to HudCues.ICON_OFFSET_B,
            ),
            frame(
                cue(HudCues.T_HOLD_B), HudEasing.IN,
                OP to 1.0, SX to 1.0, SY to 1.0, TX to HudCues.ICON_OFFSET_B,
            ),
            frame(
                cue(HudCues.T_CONTRACT), HudEasing.SMOOTH,
                OP to 1.0, SX to 1.0, SY to 1.0, TX to HudCues.ICON_OFFSET_C,
            ),
            frame(
                cue(HudCues.T_HOLD_C), HudEasing.IN,
                OP to 1.0, SX to 1.0, SY to 1.0, TX to HudCues.ICON_OFFSET_C,
            ),
        )
        builder.add(
            HudNode.RIPPLE_HOST,
            frame(cue(0.00), null, TX to 0.0),
            frame(cue(HudCues.T_EXPAND), HudEasing.IN, TX to 0.0),
            frame(cue(HudCues.T_MOVE), HudEasing.SMOOTH, TX to HudCues.ICON_OFFSET_B),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, TX to HudCues.ICON_OFFSET_B),
            frame(cue(HudCues.T_CONTRACT), HudEasing.SMOOTH, TX to HudCues.ICON_OFFSET_C),
        )
        builder.add(
            HudNode.CIRCLE_FORM,
            frame(cue(0.00), null, OP to 0.0),
            frame(cue(HudCues.T_START), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_APPEAR), HudEasing.OUT, OP to 1.0),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, OP to 1.0),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, OP to 0.0),
        )
        builder.add(
            HudNode.SQUARE_FORM,
            frame(cue(0.00), null, OP to 0.0),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, OP to 1.0),
        )
        builder.add(
            HudNode.TITLE_HOST,
            frame(cue(0.00), null, OP to 0.0),
            frame(cue(HudCues.T_MOVE), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_TITLE), HudEasing.OUT, OP to 1.0),
            frame(cue(HudCues.T_HOLD_B), HudEasing.IN, OP to 1.0),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, OP to 0.0),
        )
        builder.add(
            HudNode.NUM_HOST,
            frame(cue(0.00), null, OP to 0.0),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_NUM_IN), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_NUM_READY), HudEasing.OUT, OP to 1.0),
            frame(cue(HudCues.T_HOLD_C), HudEasing.IN, OP to 1.0),
        )
        builder.add(
            HudNode.RIPPLE_RISE,
            frame(cue(0.00), null, TY to HudCues.RIPPLE_RISE_OFFSET),
            frame(cue(HudCues.T_EXPAND), HudEasing.IN, TY to HudCues.RIPPLE_RISE_OFFSET),
            frame(cue(HudCues.T_EXPAND + 0.02), HudEasing.OUT, TY to HudCues.RIPPLE_RISE_OFFSET),
            frame(cue(HudCues.T_HOLD_B), HudEasing.OUT, TY to 0.0),
            frame(cue(HudCues.T_CONTRACT), HudEasing.IN_OUT, TY to 0.0),
        )
        builder.add(HudNode.RIPPLE_INNER, *ripple(options, cue(HudCues.T_EXPAND), 1.5, 0.50))
        builder.add(HudNode.RIPPLE_MID, *ripple(options, cue(HudCues.T_EXPAND), 2.0, 0.50))
        builder.add(HudNode.RIPPLE_OUTER, *ripple(options, cue(HudCues.T_EXPAND), 2.5, 0.60))

        return builder.build(cue(HudCues.T_HOLD_C), cue(HudCues.T_CLOSE))
    }

    /**
     * The simple timeline (`HudAnimations.cs:221-247`).
     *
     * Cue table (before `MapCueSimple`, 5 s baseline):
     *  * `SimplePillAppear`  0.00 opacity 0 scale 0.6 → 0.05 opacity 1 scale 1 → 0.75 hold
     *  * `SimpleFadeIn`      `BoltIcon` and `NumHost`: 0.00 0 → 0.05 0 → 0.08 1 → 0.75 1
     *  * `SimpleScaleOut`    `ScaleHost` 1 → 0.75 1 → 0.80 0
     *
     * @param includeScaleOut `true` for a transient simple show (the desktop then runs all three,
     *   including the collapse), `false` for a persistent one.
     */
    fun simple(options: HudAnimationOptions, includeScaleOut: Boolean): HudTimeline {
        val durationMs = (options.clampedDurationSeconds * 1000.0).roundToLong()
        val builder = TimelineBuilder(HudTimelineKind.SIMPLE, durationMs)
        fun cue(value: Double): Double = HudCues.mapSimple(options, value)

        builder.add(
            HudNode.PILL,
            frame(cue(0.00), null, OP to 0.0, SX to 0.6, SY to 0.6),
            frame(cue(HudCues.T_SIMPLE_APPEAR), HudEasing.OUT, OP to 1.0, SX to 1.0, SY to 1.0),
            frame(cue(HudCues.T_SIMPLE_HOLD), HudEasing.IN, OP to 1.0, SX to 1.0, SY to 1.0),
        )
        val fadeIn = arrayOf(
            frame(cue(0.00), null, OP to 0.0),
            frame(cue(HudCues.T_SIMPLE_APPEAR), HudEasing.IN, OP to 0.0),
            frame(cue(HudCues.T_SIMPLE_FADE), HudEasing.OUT, OP to 1.0),
            frame(cue(HudCues.T_SIMPLE_HOLD), HudEasing.IN, OP to 1.0),
        )
        builder.add(HudNode.BOLT_ICON, *fadeIn)
        builder.add(HudNode.NUM_HOST, *fadeIn)
        if (includeScaleOut) {
            builder.add(
                HudNode.SCALE_HOST,
                frame(cue(0.00), null, SX to 1.0, SY to 1.0),
                frame(cue(HudCues.T_SIMPLE_HOLD), HudEasing.IN, SX to 1.0, SY to 1.0),
                frame(cue(HudCues.T_SIMPLE_CLOSE), HudEasing.IN, SX to 0.0, SY to 0.0),
            )
        }
        return builder.build(cue(HudCues.T_SIMPLE_HOLD), cue(HudCues.T_SIMPLE_CLOSE))
    }

    /**
     * The fixed 180 ms retract `CloseFromC()` (`HudAnimations.cs:253-263`): `ScaleHost` 1 → 0 with
     * `KS_In`, used for persistent closes and for the hide half of a content switch.
     */
    fun hide(): HudTimeline = TimelineBuilder(HudTimelineKind.HIDE, HudCues.HIDE_DURATION_MS)
        .apply {
            add(
                HudNode.SCALE_HOST,
                frame(0.0, null, SX to 1.0, SY to 1.0),
                frame(1.0, HudEasing.IN, SX to 0.0, SY to 0.0),
            )
        }
        .build(holdCue = 1.0, closeCue = 1.0)

    /**
     * `Ripple(o, endScale, peakOp)`: one expanding ring. [expandCue] is the already mapped
     * `TExpand` cue; the ring peaks two cue hundredths later.
     */
    private fun ripple(
        options: HudAnimationOptions,
        expandCue: Double,
        endScale: Double,
        peakOpacity: Double,
    ): Array<HudKeyframe> {
        val target = endScale * options.clampedRippleSpread
        val peak = minOf(1.0, peakOpacity * options.clampedRippleIntensity)
        val peakCue = expandCue + (HudCues.mapFull(options, HudCues.T_EXPAND + 0.02) -
            HudCues.mapFull(options, HudCues.T_EXPAND))
        val holdCue = HudCues.mapFull(options, HudCues.T_HOLD_B)
        val contractCue = HudCues.mapFull(options, HudCues.T_CONTRACT)
        return arrayOf(
            frame(0.0, null, OP to 0.0, SX to 0.0, SY to 0.0),
            frame(expandCue, HudEasing.IN, OP to 0.0, SX to 0.0, SY to 0.0),
            frame(peakCue, HudEasing.OUT, OP to peak, SX to 0.05, SY to 0.05),
            frame(holdCue, HudEasing.OUT, OP to peak, SX to target, SY to target),
            frame(contractCue, HudEasing.IN_OUT, OP to 0.0, SX to target, SY to target),
        )
    }

    private fun frame(
        cue: Double,
        easing: HudEasing?,
        vararg values: Pair<HudChannel, Double>,
    ): HudKeyframe = HudKeyframe(cue, easing, values.toMap())

    private class TimelineBuilder(
        private val kind: HudTimelineKind,
        private val durationMs: Long,
    ) {
        private val nodes = LinkedHashMap<HudNode, MutableList<HudKeyframe>>()

        fun add(node: HudNode, vararg frames: HudKeyframe) {
            nodes.getOrPut(node) { mutableListOf() }.addAll(frames)
        }

        fun build(holdCue: Double, closeCue: Double): HudTimeline {
            val tracks = HashMap<HudNode, Map<HudChannel, HudTrack>>(nodes.size)
            for ((node, frames) in nodes) {
                val channels = LinkedHashMap<HudChannel, MutableList<Pair<Double, HudKeyframe>>>()
                for (keyframe in frames) {
                    for (channel in keyframe.values.keys) {
                        channels.getOrPut(channel) { mutableListOf() }.add(keyframe.cue to keyframe)
                    }
                }
                val nodeTracks = HashMap<HudChannel, HudTrack>(channels.size)
                for ((channel, entries) in channels) {
                    val sorted = entries.sortedBy { it.first }
                    nodeTracks[channel] = HudTrack(
                        sorted.map { it.first }.toDoubleArray(),
                        sorted.map { it.second.values.getValue(channel) }.toDoubleArray(),
                        sorted.map { it.second.easing }.toTypedArray(),
                    )
                }
                tracks[node] = nodeTracks
            }
            return HudTimeline(
                kind = kind,
                durationMs = durationMs,
                holdMs = (holdCue * durationMs).roundToLong(),
                closeMs = (closeCue * durationMs).roundToLong(),
                tracks = tracks,
            )
        }
    }
}

/** Anything the animator can drive; [OverlayHudView] is the production implementation. */
interface HudAnimationTarget {

    /** Applies one sampled frame. Called on the main thread once per animation frame. */
    fun applyAnimationState(state: HudAnimationState)
}

/**
 * Plays [HudTimeline]s and pushes one [HudAnimationState] per frame into [target].
 *
 * `ValueAnimator` already ticks on the `Choreographer` frame clock, so no custom frame callback is
 * needed. All methods must be called on the main thread, which is where the overlay window and the
 * service's UI-facing work live.
 *
 * The class also owns the product-level "隐去 → 内容切换 → 唤出" (hide → swap profile content →
 * reveal) sequence used for profile switching and auto-cycling: [swapContent].
 */
class HudAnimator(private val target: HudAnimationTarget) {

    private var current: ValueAnimator? = null

    /** True while a timeline (or one phase of [swapContent]) is playing. */
    val isRunning: Boolean get() = current?.isRunning == true

    /** Stops the running timeline and leaves the target at its last applied frame. */
    fun cancel() {
        val running = current
        current = null
        running?.cancel()
    }

    /**
     * Plays the reveal for [simple] mode.
     *
     * @param transient `true` renders the transient timeline (including `ScaleOut`/
     *   `SimpleScaleOut`), which collapses the HUD at the end; `false` renders the persistent set
     *   and settles on [HudAnimationStates.persistentFinalState].
     */
    fun playReveal(
        options: HudAnimationOptions,
        simple: Boolean,
        transient: Boolean,
        onComplete: (() -> Unit)? = null,
    ) {
        // The desktop sets the document-order initial state (full) or the simple C state (simple)
        // before the timeline starts; the timeline itself only animates part of the tree.
        val baseline = if (simple) HudAnimationStates.simpleCState() else HudAnimationStates.initial()
        val timeline = if (simple) {
            HudTimelines.simple(options, includeScaleOut = transient)
        } else {
            HudTimelines.full(options, includeScaleOut = transient)
        }
        start(
            timeline = timeline,
            baseline = baseline,
            settleToPersistentState = !transient,
            onComplete = onComplete,
        )
    }

    /**
     * Plays the fixed 180 ms scale-out retract (`CloseFromC`). The target keeps the collapsed state
     * when the callback fires, so a caller can safely detach the window there.
     */
    fun playHide(onComplete: (() -> Unit)? = null) {
        // HideAnimatedAsync forces the persistent final state before retracting, so the retract
        // always starts from the settled C state.
        val baseline = HudAnimationStates.persistentFinalState()
        target.applyAnimationState(baseline)
        start(
            timeline = HudTimelines.hide(),
            baseline = baseline,
            settleToPersistentState = false,
            onComplete = onComplete,
        )
    }

    /**
     * The first-class content-switch sequence: **hide → swap profile content → reveal**.
     *
     * Matches `CustomHudRuntime` (`:497-502`) and `CustomHudRuntime.cs:82-89`: a profile change is
     * never an in-place morph. [swap] runs on the main thread between the two timelines and is
     * expected to install the new render data on the target.
     */
    fun swapContent(
        options: HudAnimationOptions,
        simple: Boolean,
        transient: Boolean,
        swap: () -> Unit,
        onComplete: (() -> Unit)? = null,
    ) {
        start(
            timeline = HudTimelines.hide(),
            baseline = HudAnimationStates.persistentFinalState(),
            settleToPersistentState = false,
        ) {
            target.applyAnimationState(HudAnimationStates.initial())
            swap()
            playReveal(options, simple, transient, onComplete)
        }
    }

    private fun start(
        timeline: HudTimeline,
        baseline: HudAnimationState,
        settleToPersistentState: Boolean,
        onComplete: (() -> Unit)? = null,
    ) {
        cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = timeline.durationMs
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            // LinearInterpolator, so the animated fraction is exactly elapsed / duration.
            val frame = timeline.stateAt(animation.animatedFraction.toDouble(), baseline)
            target.applyAnimationState(baseline.overlay(frame))
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (current === animation) current = null
                if (cancelled) return
                val finalState = if (settleToPersistentState) {
                    HudAnimationStates.persistentFinalState()
                } else {
                    baseline.overlay(timeline.stateAt(1.0, baseline))
                }
                target.applyAnimationState(finalState)
                onComplete?.invoke()
            }
        })
        current = animator
        animator.start()
    }
}
