package com.glacierglimmer.endfieldchargeplus.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig

/**
 * Verifies the ported ECP animation language against the desktop tables quoted in
 * `docs/audit/01-windows-core.md` §5.5-§5.8 and `Animations/HudAnimations.cs`.
 */
class HudAnimationTimelineTest {

    private val options = HudAnimationOptions()

    // ---- cue mapping --------------------------------------------------------------------------

    @Test
    fun `MapCue is the identity at the 6 second baseline`() {
        for (cue in listOf(0.0, 0.04, 0.07, 0.09, 0.12, 0.20, 0.30, 0.36, 0.42, 0.86, 0.89, 1.0)) {
            assertEquals(cue, HudCues.mapFull(options, cue), 1e-9)
        }
    }

    @Test
    fun `MapCueSimple is the identity at its own 5 second baseline`() {
        val fiveSeconds = options.copy(durationSeconds = 5.0)
        for (cue in listOf(0.0, 0.05, 0.08, 0.5, 0.75, 0.8, 1.0)) {
            assertEquals(cue, HudCues.mapSimple(fiveSeconds, cue), 1e-9)
        }
    }

    @Test
    fun `MapCue keeps the absolute intro duration when the user shortens the timeline`() {
        val short = options.copy(durationSeconds = 3.0)
        // introFrac = 0.42 * 6 / 3 = 0.84, i.e. the intro still lasts 0.42 * 6 = 2.52 s.
        assertEquals(0.84, HudCues.mapFull(short, HudCues.INTRO_END_CUE), 1e-9)
        assertEquals(0.42 * HudCues.BASELINE_SECONDS, HudCues.mapFull(short, 0.42) * 3.0, 1e-9)
        // The tail is stretched over the remaining time and still ends exactly at 1.
        assertEquals(1.0, HudCues.mapFull(short, 1.0), 1e-9)
        assertTrue(HudCues.mapFull(short, HudCues.T_CLOSE) < 1.0)
    }

    @Test
    fun `MapCue stretches the tail when the user lengthens the timeline`() {
        val long = options.copy(durationSeconds = 10.0)
        // introFrac = 0.42 * 6 / 10 = 0.252
        assertEquals(0.252, HudCues.mapFull(long, HudCues.INTRO_END_CUE), 1e-9)
        // The mapped cue fraction shrinks, but the absolute cue time still grows.
        assertTrue(HudCues.mapFull(long, HudCues.T_HOLD_C) < HudCues.T_HOLD_C)
        assertTrue(
            HudCues.mapFull(long, HudCues.T_HOLD_C) * 10.0 >
                HudCues.T_HOLD_C * HudCues.BASELINE_SECONDS,
        )
        assertEquals(1.0, HudCues.mapFull(long, 1.0), 1e-9)
    }

    @Test
    fun `animation option clamps match the desktop`() {
        assertEquals(3.0, HudAnimationOptions(durationSeconds = 1.0).clampedDurationSeconds, 1e-9)
        assertEquals(10.0, HudAnimationOptions(durationSeconds = 42.0).clampedDurationSeconds, 1e-9)
        assertEquals(0.0, HudAnimationOptions(bounceStrength = -1.0).clampedBounceStrength, 1e-9)
        assertEquals(0.5, HudAnimationOptions(bounceStrength = 0.9).clampedBounceStrength, 1e-9)
        assertEquals(0.0, HudAnimationOptions(rippleIntensity = -2.0).clampedRippleIntensity, 1e-9)
        assertEquals(2.0, HudAnimationOptions(rippleIntensity = 5.0).clampedRippleIntensity, 1e-9)
        assertEquals(0.5, HudAnimationOptions(rippleSpread = 0.1).clampedRippleSpread, 1e-9)
        assertEquals(1.5, HudAnimationOptions(rippleSpread = 9.0).clampedRippleSpread, 1e-9)
    }

    @Test
    fun `options can be read from the persisted configuration`() {
        val config = AppConfig(
            displayDurationSeconds = 7.5,
            bounceStrength = 0.4,
            rippleIntensity = 1.5,
            rippleSpread = 1.25,
        )
        val fromConfig = HudAnimationOptions.from(config)
        assertEquals(7.5, fromConfig.clampedDurationSeconds, 1e-9)
        assertEquals(0.4, fromConfig.clampedBounceStrength, 1e-9)
        assertEquals(1.5, fromConfig.clampedRippleIntensity, 1e-9)
        assertEquals(1.25, fromConfig.clampedRippleSpread, 1e-9)
    }

    // ---- easings ------------------------------------------------------------------------------

    @Test
    fun `easings are pinned at both ends`() {
        for (easing in listOf(HudEasing.IN, HudEasing.OUT, HudEasing.IN_OUT, HudEasing.SMOOTH)) {
            assertEquals(0.0, easing.ease(0.0), 1e-6)
            assertEquals(1.0, easing.ease(1.0), 1e-6)
        }
        // KS_InOut and KS_Smooth are symmetric about the midpoint.
        assertEquals(0.5, HudEasing.IN_OUT.ease(0.5), 1e-6)
        assertEquals(0.5, HudEasing.SMOOTH.ease(0.5), 1e-6)
    }

    @Test
    fun `KS_In starts slow and KS_Out starts fast`() {
        assertTrue(HudEasing.IN.ease(0.5) < 0.5)
        assertTrue(HudEasing.OUT.ease(0.25) > 0.25)
        assertTrue(HudEasing.OUT.ease(0.25) > HudEasing.IN.ease(0.25))
    }

    @Test
    fun `BackOut overshoots and settles back to one`() {
        val backOut = HudEasing.backOut(AppConfig.DEFAULT_BOUNCE_STRENGTH)
        assertTrue(backOut.ease(0.5) > 1.0)
        assertEquals(1.0, backOut.ease(1.0), 1e-6)
        val stronger = HudEasing.backOut(0.5)
        assertTrue(stronger.ease(0.5) > backOut.ease(0.5))
    }

    // ---- full timeline cue table --------------------------------------------------------------

    @Test
    fun `full timeline reports the desktop duration and cues`() {
        val timeline = HudTimelines.full(options, includeScaleOut = true)
        assertEquals(HudTimelineKind.FULL, timeline.kind)
        assertEquals(6000L, timeline.durationMs)
        assertEquals(5160L, timeline.holdMs)
        assertEquals(5340L, timeline.closeMs)
    }

    @Test
    fun `full timeline starts from the document order initial state`() {
        val state = HudTimelines.full(options, includeScaleOut = true).stateAt(0.0)
        assertEquals(0f, state[HudNode.PILL].opacity, 1e-4f)
        assertEquals(0.6f, state[HudNode.PILL].scaleX, 1e-4f)
        assertEquals(30f, state[HudNode.PILL].cornerRadius, 1e-4f)
        assertEquals(60f, state[HudNode.PILL].height, 1e-4f)
        assertEquals(0f, state[HudNode.BOLT_ICON].opacity, 1e-4f)
        assertEquals(0.4f, state[HudNode.BOLT_ICON].scaleX, 1e-4f)
        assertEquals(0f, state[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(0f, state[HudNode.CIRCLE_FORM].opacity, 1e-4f)
        assertEquals(0f, state[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(0f, state[HudNode.TITLE_HOST].opacity, 1e-4f)
        assertEquals(0f, state[HudNode.NUM_HOST].opacity, 1e-4f)
        assertEquals(1f, state[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(16f, state[HudNode.RIPPLE_RISE].translateY, 1e-4f)
    }

    @Test
    fun `full timeline title and ripple phase runs from 0_00 to 0_36`() {
        val timeline = HudTimelines.full(options, includeScaleOut = true)

        // cue 0.07 (TAppear): the pill is still invisible, the circle form is fully in.
        val appear = timeline.stateAt(0.07)
        assertEquals(0f, appear[HudNode.PILL].opacity, 1e-4f)
        assertEquals(1f, appear[HudNode.CIRCLE_FORM].opacity, 1e-4f)

        // cue 0.09 (TPillOut, BackOut): the pill pops in.
        val popped = timeline.stateAt(0.09)
        assertEquals(1f, popped[HudNode.PILL].opacity, 1e-4f)
        assertEquals(1f, popped[HudNode.PILL].scaleX, 1e-4f)

        // cue 0.12 (TExpand): radius 18, height 90, the icon is back to scale 1 at X = 0.
        val expanded = timeline.stateAt(0.12)
        assertEquals(18f, expanded[HudNode.PILL].cornerRadius, 1e-4f)
        assertEquals(90f, expanded[HudNode.PILL].height, 1e-4f)
        assertEquals(90f, expanded[HudNode.RIPPLE_HOST].height, 1e-4f)
        assertEquals(1f, expanded[HudNode.BOLT_ICON].scaleX, 1e-4f)
        assertEquals(0f, expanded[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(16f, expanded[HudNode.RIPPLE_RISE].translateY, 1e-4f)

        // cue 0.14: the rings show at their beginning scale.
        val rippleStart = timeline.stateAt(HudCues.mapFull(options, 0.14))
        assertEquals(0.5f, rippleStart[HudNode.RIPPLE_INNER].opacity, 1e-4f)
        assertEquals(0.05f, rippleStart[HudNode.RIPPLE_INNER].scaleX, 1e-4f)

        // cue 0.20 (TMove): the icon reaches X = -179.
        assertEquals(-179f, timeline.stateAt(0.20)[HudNode.BOLT_ICON].translateX, 1e-4f)

        // cue 0.25 (TTitle): the title is visible.
        assertEquals(1f, timeline.stateAt(0.25)[HudNode.TITLE_HOST].opacity, 1e-4f)

        // cue 0.30 (THoldB): B state held, square form still hidden, rings at full spread.
        val holdB = timeline.stateAt(0.30)
        assertEquals(-179f, holdB[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(90f, holdB[HudNode.PILL].height, 1e-4f)
        assertEquals(0f, holdB[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(1f, holdB[HudNode.TITLE_HOST].opacity, 1e-4f)
        assertEquals(0.5f, holdB[HudNode.RIPPLE_INNER].opacity, 1e-4f)
        assertEquals(1.5f, holdB[HudNode.RIPPLE_INNER].scaleX, 1e-4f)
        assertEquals(2.0f, holdB[HudNode.RIPPLE_MID].scaleX, 1e-4f)
        assertEquals(2.5f, holdB[HudNode.RIPPLE_OUTER].scaleX, 1e-4f)
        assertEquals(0.6f, holdB[HudNode.RIPPLE_OUTER].opacity, 1e-4f)
        assertEquals(0f, holdB[HudNode.RIPPLE_RISE].translateY, 1e-4f)
    }

    @Test
    fun `full timeline contracts into the C state at 0_36`() {
        val timeline = HudTimelines.full(options, includeScaleOut = true)
        val contracted = timeline.stateAt(0.36)
        assertEquals(30f, contracted[HudNode.PILL].cornerRadius, 1e-4f)
        assertEquals(60f, contracted[HudNode.PILL].height, 1e-4f)
        assertEquals(-245f, contracted[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(-245f, contracted[HudNode.RIPPLE_HOST].translateX, 1e-4f)
        assertEquals(0f, contracted[HudNode.CIRCLE_FORM].opacity, 1e-4f)
        assertEquals(1f, contracted[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(0f, contracted[HudNode.TITLE_HOST].opacity, 1e-4f)
        assertEquals(0f, contracted[HudNode.NUM_HOST].opacity, 1e-4f)
        assertEquals(0f, contracted[HudNode.RIPPLE_INNER].opacity, 1e-4f)
    }

    @Test
    fun `full timeline C state holds then collapses at 0_89`() {
        val timeline = HudTimelines.full(options, includeScaleOut = true)
        val holdC = timeline.stateAt(0.86)
        assertEquals(1f, holdC[HudNode.NUM_HOST].opacity, 1e-4f)
        assertEquals(1f, holdC[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(-245f, holdC[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(1f, holdC[HudNode.PILL].opacity, 1e-4f)

        // The scale-out is the only thing that changes between the hold and the close.
        assertEquals(0f, timeline.stateAt(0.89)[HudNode.SCALE_HOST].scaleX, 1e-4f)
        // FillMode.Forward keeps the last keyframe after the timeline ends.
        assertEquals(0f, timeline.stateAt(1.0)[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(1f, timeline.stateAt(0.5)[HudNode.SCALE_HOST].scaleX, 1e-4f)

        // A persistent show runs the same table minus ScaleOut.
        assertEquals(1f, HudTimelines.full(options, includeScaleOut = false).stateAt(1.0)[HudNode.SCALE_HOST].scaleX, 1e-4f)
    }

    @Test
    fun `ripple intensity and spread scale the rings`() {
        val boosted = options.copy(rippleIntensity = 2.0, rippleSpread = 1.5)
        val state = HudTimelines.full(boosted, includeScaleOut = true).stateAt(0.30)
        // peak = min(1, 0.5 * 2) = 1 for the inner ring, target = 1.5 * 1.5 = 2.25.
        assertEquals(1f, state[HudNode.RIPPLE_INNER].opacity, 1e-4f)
        assertEquals(2.25f, state[HudNode.RIPPLE_INNER].scaleX, 1e-4f)
        // peak = min(1, 0.6 * 2) = 1 for the outer ring, target = 2.5 * 1.5 = 3.75.
        assertEquals(3.75f, state[HudNode.RIPPLE_OUTER].scaleX, 1e-4f)
    }

    // ---- simple timeline ----------------------------------------------------------------------

    @Test
    fun `simple timeline shows the final HUD almost immediately`() {
        val timeline = HudTimelines.simple(options, includeScaleOut = true)
        assertEquals(HudTimelineKind.SIMPLE, timeline.kind)
        assertEquals(6000L, timeline.durationMs)

        assertEquals(0f, timeline.stateAt(0.0)[HudNode.PILL].opacity, 1e-4f)
        assertEquals(0.6f, timeline.stateAt(0.0)[HudNode.PILL].scaleX, 1e-4f)

        // MapCueSimple(0.05) with d = 6 is 0.0417 -> the pill appears within 250 ms.
        val appearCue = HudCues.mapSimple(options, HudCues.T_SIMPLE_APPEAR)
        assertTrue(appearCue * timeline.durationMs < 300.0)
        assertEquals(1f, timeline.stateAt(appearCue)[HudNode.PILL].opacity, 1e-4f)

        // By the end of the 0.08 fade the numbers and the icon are fully visible.
        val settled = timeline.stateAt(HudCues.mapSimple(options, HudCues.T_SIMPLE_FADE))
        assertEquals(1f, settled[HudNode.NUM_HOST].opacity, 1e-4f)
        assertEquals(1f, settled[HudNode.BOLT_ICON].opacity, 1e-4f)
        assertEquals(1f, settled[HudNode.PILL].opacity, 1e-4f)

        // The fixed order of the simple cues.
        assertEquals(4478L, timeline.holdMs)
        assertEquals(4783L, timeline.closeMs)
        assertEquals(0f, timeline.stateAt(HudCues.mapSimple(options, HudCues.T_SIMPLE_CLOSE))[HudNode.SCALE_HOST].scaleX, 1e-4f)
    }

    @Test
    fun `persistent simple timeline does not collapse`() {
        val timeline = HudTimelines.simple(options, includeScaleOut = false)
        assertEquals(1f, timeline.stateAt(1.0)[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(1f, timeline.stateAt(1.0)[HudNode.NUM_HOST].opacity, 1e-4f)
    }

    // ---- the fixed retract --------------------------------------------------------------------

    @Test
    fun `hide is the fixed 180 ms scale out`() {
        val timeline = HudTimelines.hide()
        assertEquals(HudTimelineKind.HIDE, timeline.kind)
        assertEquals(180L, timeline.durationMs)
        assertEquals(1f, timeline.stateAt(0.0)[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(0f, timeline.stateAt(1.0)[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertTrue(timeline.stateAt(0.5)[HudNode.SCALE_HOST].scaleX < 1f)
    }

    // ---- canned states ------------------------------------------------------------------------

    @Test
    fun `canned states match ResetToInitial, SetSimpleCState and SetPersistentFinalState`() {
        val initial = HudAnimationStates.initial()
        assertEquals(0f, initial[HudNode.PILL].opacity, 1e-4f)
        assertEquals(0.4f, initial[HudNode.BOLT_ICON].scaleX, 1e-4f)
        assertEquals(0f, initial[HudNode.BOLT_ICON].translateX, 1e-4f)

        val simpleC = HudAnimationStates.simpleCState()
        assertEquals(0f, simpleC[HudNode.PILL].opacity, 1e-4f)
        assertEquals(1f, simpleC[HudNode.BOLT_ICON].scaleX, 1e-4f)
        assertEquals(-245f, simpleC[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(1f, simpleC[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(0f, simpleC[HudNode.NUM_HOST].opacity, 1e-4f)

        val finalState = HudAnimationStates.persistentFinalState()
        assertEquals(1f, finalState[HudNode.PILL].opacity, 1e-4f)
        assertEquals(1f, finalState[HudNode.SCALE_HOST].scaleX, 1e-4f)
        assertEquals(-245f, finalState[HudNode.RIPPLE_HOST].translateX, 1e-4f)
        assertEquals(1f, finalState[HudNode.BOLT_ICON].opacity, 1e-4f)
        assertEquals(-245f, finalState[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(1f, finalState[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(0f, finalState[HudNode.TITLE_HOST].opacity, 1e-4f)
        assertEquals(1f, finalState[HudNode.NUM_HOST].opacity, 1e-4f)

        assertEquals(0f, HudAnimationStates.closed()[HudNode.SCALE_HOST].scaleX, 1e-4f)
    }

    @Test
    fun `timeline tracks are per channel and hold their end values`() {
        val timeline = HudTimelines.full(options, includeScaleOut = true)
        val node = timeline.nodeStateAt(HudNode.PILL, 0.0)
        assertFalse(node.opacity > 0f)
        // PILL carries three independent tracks (appear, corner, height); all keep their values.
        assertEquals(30f, node.cornerRadius, 1e-4f)
        assertEquals(60f, node.height, 1e-4f)
    }

    @Test
    fun `a simple reveal is layered over the simple C state`() {
        // The driver seeds each frame with the baseline the desktop sets before playing
        // (SetSimpleCState), so the channels the simple timeline does not animate keep their values.
        val baseline = HudAnimationStates.simpleCState()
        val firstFrame = baseline.overlay(
            HudTimelines.simple(options, includeScaleOut = true).stateAt(0.0, baseline),
        )
        assertEquals(1f, firstFrame[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(0f, firstFrame[HudNode.CIRCLE_FORM].opacity, 1e-4f)
        assertEquals(-245f, firstFrame[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertEquals(1f, firstFrame[HudNode.BOLT_ICON].scaleX, 1e-4f)
        assertEquals(0f, firstFrame[HudNode.TITLE_HOST].opacity, 1e-4f)
        assertEquals(0f, firstFrame[HudNode.RIPPLE_INNER].opacity, 1e-4f)
        // ...while the timeline owns the pill and the number host.
        assertEquals(0f, firstFrame[HudNode.PILL].opacity, 1e-4f)
        assertEquals(0.6f, firstFrame[HudNode.PILL].scaleX, 1e-4f)
        assertEquals(0f, firstFrame[HudNode.NUM_HOST].opacity, 1e-4f)
    }

    @Test
    fun `a retract keeps the settled C state for the nodes it does not animate`() {
        val baseline = HudAnimationStates.persistentFinalState()
        val midHide = baseline.overlay(HudTimelines.hide().stateAt(0.5, baseline))
        assertEquals(1f, midHide[HudNode.SQUARE_FORM].opacity, 1e-4f)
        assertEquals(1f, midHide[HudNode.NUM_HOST].opacity, 1e-4f)
        assertEquals(0f, midHide[HudNode.CIRCLE_FORM].opacity, 1e-4f)
        assertEquals(-245f, midHide[HudNode.BOLT_ICON].translateX, 1e-4f)
        assertTrue(midHide[HudNode.SCALE_HOST].scaleX < 1f)
    }
}
