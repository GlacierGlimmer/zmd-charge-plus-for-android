package com.glacierglimmer.endfieldchargeplus.overlay

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [HudPositionMath] for every desktop anchor, both orientations, the per-orientation free
 * position and the safe-area clamping. Pure JVM: no Android framework is touched.
 */
class HudPositionMathTest {

    private val frame = HudDisplayFrame(
        widthPx = 1080,
        heightPx = 2400,
        insets = HudInsets(left = 0, top = 80, right = 0, bottom = 120),
    )

    private val hudWidth = 448
    private val hudHeight = 48

    private fun config(
        position: String = "TopCenter",
        mode: String = "Preset",
        offsetX: Int = 0,
        offsetY: Int = 0,
        customX: Int = 0,
        customY: Int = 0,
        portraitX: Int = -1,
        portraitY: Int = -1,
        landscapeX: Int = -1,
        landscapeY: Int = -1,
    ): AppConfig = AppConfig(
        positionMode = mode,
        hudPosition = position,
        hudOffsetX = offsetX,
        hudOffsetY = offsetY,
        hudCustomX = customX,
        hudCustomY = customY,
        android = com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings(
            overlayXPortrait = portraitX,
            overlayYPortrait = portraitY,
            overlayXLandscape = landscapeX,
            overlayYLandscape = landscapeY,
        ),
    )

    private fun resolve(
        config: AppConfig,
        landscape: Boolean = false,
        width: Int = hudWidth,
        height: Int = hudHeight,
    ): HudWindowPosition = HudPositionMath.resolve(config, frame, landscape, width, height)

    @Test
    fun `all nine presets resolve inside the safe area`() {
        // safe area: x 0..1080, y 80..2280; safe width 1080, safe height 2200.
        assertEquals(HudWindowPosition(16, 96), resolve(config(position = HudPosition.TOP_LEFT.wireName())))
        assertEquals(HudWindowPosition(316, 96), resolve(config(position = HudPosition.TOP_CENTER.wireName())))
        assertEquals(HudWindowPosition(616, 96), resolve(config(position = HudPosition.TOP_RIGHT.wireName())))
        assertEquals(HudWindowPosition(16, 1156), resolve(config(position = HudPosition.CENTER_LEFT.wireName())))
        assertEquals(HudWindowPosition(316, 1156), resolve(config(position = HudPosition.CENTER.wireName())))
        assertEquals(HudWindowPosition(616, 1156), resolve(config(position = HudPosition.CENTER_RIGHT.wireName())))
        assertEquals(HudWindowPosition(16, 2216), resolve(config(position = HudPosition.BOTTOM_LEFT.wireName())))
        assertEquals(HudWindowPosition(316, 2216), resolve(config(position = HudPosition.BOTTOM_CENTER.wireName())))
        assertEquals(HudWindowPosition(616, 2216), resolve(config(position = HudPosition.BOTTOM_RIGHT.wireName())))
    }

    @Test
    fun `preset offsets are applied and then clamped into the safe area`() {
        val clampLow = config(position = "TopCenter", offsetX = -500, offsetY = -500)
        assertEquals(HudWindowPosition(0, 80), resolve(clampLow))

        val clampHigh = config(position = "TopCenter", offsetX = 5000, offsetY = 5000)
        // max offset x = 1080 - 448 = 632, max offset y = 2200 - 48 = 2152.
        assertEquals(HudWindowPosition(632, 2280 - 48), resolve(clampHigh))
    }

    @Test
    fun `the left safe inset shifts every coordinate`() {
        val insetFrame = frame.copy(insets = HudInsets(left = 40, top = 80, right = 24, bottom = 120))
        // safe area: x 40..1056 (width 1016), y 80..2280.
        val centred = HudPositionMath.resolve(
            config = config(position = "TopCenter"),
            frame = insetFrame,
            landscape = false,
            hudWidthPx = hudWidth,
            hudHeightPx = hudHeight,
        )
        assertEquals(40 + (1016 - 448) / 2, centred.x)
        assertEquals(96, centred.y)

        val right = HudPositionMath.resolve(
            config = config(position = "TopRight"),
            frame = insetFrame,
            landscape = false,
            hudWidthPx = hudWidth,
            hudHeightPx = hudHeight,
        )
        assertEquals(40 + 1016 - 448 - HudPositionMath.MARGIN, right.x)
    }

    @Test
    fun `custom coordinates are offsets from the safe area origin`() {
        val custom = config(mode = "CustomCoordinates", customX = 100, customY = 200)
        assertEquals(HudWindowPosition(100, 280), resolve(custom))

        // The desktop ignores HudOffsetX/Y in CustomCoordinates mode.
        val customWithOffsets = config(
            mode = "CustomCoordinates",
            customX = 100,
            customY = 200,
            offsetX = 50,
            offsetY = 50,
        )
        assertEquals(HudWindowPosition(100, 280), resolve(customWithOffsets))
    }

    @Test
    fun `a dragged free position wins over the preset mode and is per orientation`() {
        val dragged = config(
            position = "BottomRight",
            portraitX = 10,
            portraitY = 20,
            landscapeX = 50,
            landscapeY = 60,
        )
        assertEquals(HudWindowPosition(10, 100), resolve(dragged, landscape = false))
        assertEquals(HudWindowPosition(50, 140), resolve(dragged, landscape = true))
    }

    @Test
    fun `a dragged free position is clamped too`() {
        // max offset x = 1080 - 448 = 632; max offset y = 2200 - 48 = 2152.
        val dragged = config(portraitX = 5000, portraitY = 5000)
        assertEquals(HudWindowPosition(632, 80 + 2152), resolve(dragged))
        // 0 is a valid dragged offset (the safe-area origin); only negative values mean "unset".
        val atOrigin = config(portraitX = 0, portraitY = 0)
        assertEquals(HudWindowPosition(0, 80), resolve(atOrigin))
    }

    @Test
    fun `a half-set free position is ignored in favour of the preset anchor`() {
        // A drag always writes both coordinates; -1 means "this orientation was never dragged".
        val halfSet = config(position = "TopRight", portraitX = 10, portraitY = -1)
        assertEquals(HudWindowPosition(616, 96), resolve(halfSet))
    }

    @Test
    fun `a HUD larger than the safe area snaps to its origin instead of going negative`() {
        val tiny = HudDisplayFrame(widthPx = 400, heightPx = 300, insets = HudInsets(top = 20))
        val position = HudPositionMath.resolve(
            config = config(position = "BottomRight"),
            frame = tiny,
            landscape = false,
            hudWidthPx = 600,
            hudHeightPx = 400,
        )
        assertEquals(HudWindowPosition(0, 20), position)
    }

    @Test
    fun `clamp keeps a dragged window inside the safe area`() {
        assertEquals(
            HudWindowPosition(632, 2232),
            HudPositionMath.clamp(frame, x = 5000, y = 5000, hudWidthPx = hudWidth, hudHeightPx = hudHeight),
        )
        assertEquals(
            HudWindowPosition(0, 80),
            HudPositionMath.clamp(frame, x = -100, y = -100, hudWidthPx = hudWidth, hudHeightPx = hudHeight),
        )
    }

    @Test
    fun `fitScale only shrinks the HUD when it would exceed the display`() {
        // 560 design units * density 3 * 0.8 = 1344 px, more than 94 % of 1080.
        val fitted = HudPositionMath.fitScale(rawScale = 0.8, frame = frame, density = 3f)
        assertTrue(fitted < 0.8f)
        assertTrue(560f * 3f * fitted <= frame.safeWidth * HudPositionMath.MAX_WIDTH_FRACTION + 0.5f)

        // A scale that already fits is left untouched.
        assertEquals(0.3f, HudPositionMath.fitScale(rawScale = 0.3, frame = frame, density = 3f), 1e-6f)

        // The configured range is always honoured (a wide display lets the maximum through).
        val wide = HudDisplayFrame(widthPx = 3000, heightPx = 2400)
        assertEquals(HudPositionMath.MAX_SCALE, HudPositionMath.fitScale(9.0, wide, 1f), 1e-6f)
    }

    @Test
    fun `safe area helper reflects the insets`() {
        assertEquals(0, frame.safeLeft)
        assertEquals(1080, frame.safeRight)
        assertEquals(80, frame.safeTop)
        assertEquals(2280, frame.safeBottom)
        assertEquals(1080, frame.safeWidth)
        assertEquals(2200, frame.safeHeight)
    }

    /**
     * `HudPosition.wire` is the export spelling used by the desktop configuration
     * (`TopCenter`); the tests spell it out instead of relying on that helper so a change there
     * cannot silently change what these cases mean.
     */
    private fun HudPosition.wireName(): String = when (this) {
        HudPosition.TOP_LEFT -> "TopLeft"
        HudPosition.TOP_CENTER -> "TopCenter"
        HudPosition.TOP_RIGHT -> "TopRight"
        HudPosition.CENTER_LEFT -> "CenterLeft"
        HudPosition.CENTER -> "Center"
        HudPosition.CENTER_RIGHT -> "CenterRight"
        HudPosition.BOTTOM_LEFT -> "BottomLeft"
        HudPosition.BOTTOM_CENTER -> "BottomCenter"
        HudPosition.BOTTOM_RIGHT -> "BottomRight"
    }
}
