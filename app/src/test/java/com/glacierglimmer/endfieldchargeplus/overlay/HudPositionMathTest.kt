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
            useDraggedPosition = portraitX >= 0 || landscapeX >= 0,
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

    @Test fun `repeated rotations use a compact landscape HUD and stay inside the display`() {
        val landscape = HudDisplayFrame(2400, 1080, HudInsets(left = 80, bottom = 120))
        val config = AppConfig(globalScale = 0.9)
        val portraitGeometry = HudPositionMath.geometry(config, frame, 3f)
        repeat(1000) { index ->
            val display = if (index % 2 == 0) landscape else frame
            val geometry = HudPositionMath.geometry(config, display, 3f)
            if (display.widthPx > display.heightPx) {
                assertTrue(geometry.width < portraitGeometry.width * 0.75)
                assertTrue(geometry.height < portraitGeometry.height)
            } else assertEquals(portraitGeometry, geometry)
            assertTrue(geometry.position.x >= display.safeLeft)
            assertTrue(geometry.position.y >= display.safeTop)
            assertTrue(geometry.position.x + geometry.width <= display.safeRight)
            assertTrue(geometry.position.y + geometry.height <= display.safeBottom)
        }
    }

    @Test fun `rotation uses independent portrait and landscape drag positions from the same geometry snapshot`() {
        val config = config(portraitX = 100, portraitY = 200, landscapeX = 300, landscapeY = 400).copy(globalScale = 0.3)
        val landscape = HudDisplayFrame(2400, 1080, HudInsets(left = 80, bottom = 120))
        val portraitGeometry = HudPositionMath.geometry(config, frame, 3f)
        val landscapeGeometry = HudPositionMath.geometry(config, landscape, 3f)
        assertEquals(HudWindowPosition(100, 280), portraitGeometry.position)
        assertEquals(HudWindowPosition(380, 400), landscapeGeometry.position)
        assertEquals(portraitGeometry, HudPositionMath.geometry(config, frame, 3f))
    }

    @Test fun `display density changes update both window dimensions without changing the configured scale`() {
        val config = AppConfig(globalScale = 0.3)
        val original = HudPositionMath.geometry(config, frame, 3f)
        val changed = HudPositionMath.geometry(config, frame, 2f)
        assertEquals(original.scale, changed.scale, 1e-6f)
        assertEquals(504, original.width)
        assertEquals(81, original.height)
        assertEquals(336, changed.width)
        assertEquals(54, changed.height)
        assertEquals(original, HudPositionMath.geometry(config, frame, 3f))
    }

    @Test fun `legacy landscape drag coordinates cannot move the selected top anchor into the middle`() {
        val landscape=HudDisplayFrame(2400,1080,HudInsets(top=80,bottom=60))
        val config=AppConfig(hudPosition="TopCenter",android=com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings(overlayXLandscape=900,overlayYLandscape=500))
        val geometry=HudPositionMath.geometry(config,landscape,3f)
        assertEquals(96,geometry.position.y)
        assertEquals((landscape.safeWidth-geometry.width)/2,geometry.position.x)
    }
    @Test fun `top anchor remains at the safe top edge throughout repeated rotations`() {
        val landscape=HudDisplayFrame(2400,1080,HudInsets(left=80,top=24,bottom=60))
        repeat(1000) {
            val display=if(it%2==0) frame else landscape
            val geometry=HudPositionMath.geometry(AppConfig(hudPosition="TopCenter"),display,3f)
            assertEquals(display.safeTop+16,geometry.position.y)
        }
    }

    @Test fun `fullscreen game removes hidden portrait bars while retaining the rotated physical cutout`() {
        val portraitInsets = HudSafeArea.resolve(HudInsets(top = 120), HudInsets(bottom = 70),
            HudInsets(top = 110), true, true, true)
        val portrait = HudDisplayFrame(1280, 2772, portraitInsets)
        val portraitGeometry = HudPositionMath.geometry(AppConfig(), portrait, 3.25f)
        val gameInsets = HudSafeArea.resolve(HudInsets(top = 120), HudInsets(right = 70),
            HudInsets(left = 110), false, false, true)
        val game = HudDisplayFrame(2772, 1280, gameInsets)
        val landscapeGeometry = HudPositionMath.geometry(AppConfig(), game, 3.25f)
        assertEquals(136, portraitGeometry.position.y)
        assertEquals(16, landscapeGeometry.position.y)
        assertEquals(110, game.safeLeft)
        assertEquals(0, game.insets.top)
        assertTrue(landscapeGeometry.width <= 896)
        assertTrue(landscapeGeometry.height < portraitGeometry.height)
        // Settled pill includes 15 design units of animation headroom, not a hidden status bar.
        val visiblePillTop = landscapeGeometry.position.y +
            (OverlayHudView.DESIGN_VIEW_HEIGHT - OverlayHudView.DESIGN_PILL_HEIGHT) *
            landscapeGeometry.scale * landscapeGeometry.density / 2
        assertTrue(visiblePillTop < 45f)
        repeat(1000) {
            assertEquals(if (it % 2 == 0) portraitGeometry else landscapeGeometry,
                HudPositionMath.geometry(AppConfig(), if (it % 2 == 0) portrait else game, 3.25f))
        }
    }

    @Test fun `showing bars again reserves only currently visible bars and optional cutout`() {
        val status = HudInsets(top = 64); val navigation = HudInsets(right = 60)
        val cutout = HudInsets(left = 110)
        assertEquals(HudInsets(left = 110, top = 64, right = 60),
            HudSafeArea.resolve(status, navigation, cutout, true, true, true))
        assertEquals(HudInsets(left = 110), HudSafeArea.resolve(status, navigation, cutout, false, false, true))
        assertEquals(HudInsets(top = 64), HudSafeArea.resolve(status, navigation, cutout, true, true, false))
        assertEquals(HudInsets(), HudSafeArea.resolve(status, navigation, cutout, false, false, false))
        val shownFrame = HudDisplayFrame(2772, 1280,
            HudSafeArea.resolve(status, navigation, cutout, true, true, true))
        val hiddenFrame = shownFrame.copy(insets = HudSafeArea.resolve(status, navigation, cutout, false, false, true))
        val shown = HudPositionMath.geometry(AppConfig(), shownFrame, 3.25f)
        val hidden = HudPositionMath.geometry(AppConfig(), hiddenFrame, 3.25f)
        assertEquals(shown.width, hidden.width)
        assertEquals(shown.height, hidden.height)
        assertEquals(64, shown.position.y - hidden.position.y)
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
