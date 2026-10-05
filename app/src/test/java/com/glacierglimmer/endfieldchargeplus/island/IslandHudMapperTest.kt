package com.glacierglimmer.endfieldchargeplus.island

import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Degradation rules of [IslandHudMapper]: nothing is invented, and text whose own slot is missing is
 * folded into the single compact line instead of being silently dropped.
 */
class IslandHudMapperTest {

    private val androidCaps: IslandCapabilities = AndroidLiveUpdateProvider.CAPABILITIES

    private val hyperCaps: IslandCapabilities = XiaomiHyperIslandProvider.CAPABILITIES

    private val fullCaps = IslandCapabilities(
        supportsTitle = true,
        supportsSubtitle = true,
        supportsProgress = true,
        supportsIcons = true,
        supportsMultipleLines = true,
        supportsCustomLayout = true,
        supportsContinuousUpdates = true,
        maxUpdateHz = 10.0,
        supportsLeftRightSplit = true,
    )

    private val titleOnlyCaps = IslandCapabilities(
        supportsTitle = true,
        supportsSubtitle = false,
        supportsProgress = false,
        supportsIcons = false,
        supportsMultipleLines = false,
        supportsCustomLayout = false,
        supportsContinuousUpdates = false,
        maxUpdateHz = 0.1,
        supportsLeftRightSplit = false,
    )

    @Test
    fun `full capability backend keeps every slot`() {
        val content = IslandHudMapper.map(sample(), fullCaps)

        assertEquals("Charging", content.title)
        assertEquals("78%", content.subtitle)
        assertEquals("25.4 W", content.shortText)
        assertEquals(78.4, content.progressPercent, 0.05)
        assertEquals("bolt", content.iconKey)
        assertEquals("#C6CA4C", content.accentColor)
    }

    @Test
    fun `android live update folds text without a left-right split and drops icons`() {
        val content = IslandHudMapper.map(sample(), androidCaps)

        assertEquals("Charging", content.title)
        assertEquals("78%", content.subtitle)
        assertTrue(content.shortText, content.shortText.contains("ECP"))
        assertTrue(content.shortText, content.shortText.contains("25.4 W"))
        assertEquals("", content.iconKey)
        assertEquals(78.4, content.progressPercent, 0.05)
    }

    @Test
    fun `title-only backend folds every other fragment into the short line`() {
        val content = IslandHudMapper.map(sample(), titleOnlyCaps)

        assertEquals("Charging", content.title)
        assertEquals("", content.subtitle)
        assertEquals("78% · ECP · 25.4 W", content.shortText)
        assertEquals(0.0, content.progressPercent, 0.0)
        assertEquals("", content.iconKey)
    }

    @Test
    fun `progress without backend support is reported as zero instead of guessed`() {
        val content = IslandHudMapper.map(sample().copy(progress = 0.99), titleOnlyCaps)

        assertEquals(0.0, content.progressPercent, 0.0)
    }

    @Test
    fun `progress is clamped to the 0 to 100 range and rounded to one decimal`() {
        assertEquals(100.0, IslandHudMapper.map(sample().copy(progress = 4.2), androidCaps).progressPercent, 0.001)
        assertEquals(0.0, IslandHudMapper.map(sample().copy(progress = -3.0), androidCaps).progressPercent, 0.001)
        assertEquals(33.3, IslandHudMapper.map(sample().copy(progress = 0.33333), androidCaps).progressPercent, 0.001)
    }

    @Test
    fun `blank title falls back to the tagline and blank fragments are not duplicated`() {
        val data = sample().copy(title = "  ", primaryText = "", secondaryText = "", rightText = "", rightSuffix = "")
        val content = IslandHudMapper.map(data, androidCaps)

        assertEquals("ECP", content.title)
        assertEquals("", content.subtitle)
        assertEquals("", content.shortText)
        assertEquals(false, content.isEmpty)
    }

    @Test
    fun `an empty frame is marked empty`() {
        val content = IslandHudMapper.map(HudRenderData(), titleOnlyCaps)

        assertEquals("", content.title)
        assertEquals("", content.subtitle)
        assertEquals("", content.shortText)
        assertTrue(content.isEmpty)
    }

    @Test
    fun `hyper island capability advertises what the templates can show`() {
        assertTrue(hyperCaps.supportsProgress)
        assertTrue(hyperCaps.supportsIcons)
        assertEquals(false, hyperCaps.supportsCustomLayout)
        assertEquals(false, hyperCaps.supportsLeftRightSplit)
        assertTrue(hyperCaps.experimental)
        assertTrue(hyperCaps.limitationKeys.isNotEmpty())
    }

    @Test
    fun `android live update capability tells the truth about the platform limits`() {
        assertEquals(false, androidCaps.supportsCustomLayout)
        assertEquals(false, androidCaps.supportsLeftRightSplit)
        assertEquals(false, androidCaps.supportsContinuousUpdates)
        assertTrue("maxUpdateHz must reflect platform throttling", androidCaps.maxUpdateHz <= 0.5)
        assertTrue(androidCaps.experimental)
        assertTrue(androidCaps.limitationKeys.contains("island_limit_no_custom_layout"))
        assertTrue(androidCaps.limitationKeys.contains("island_limit_scenario_restricted"))
    }

    private fun sample() = HudRenderData(
        tagline = "ECP",
        title = "Charging",
        primaryText = "78%",
        secondaryText = "ECP",
        rightText = "25.4",
        rightSuffix = " W",
        progress = 0.784,
        leftIcon = "bolt",
        rightIcon = "battery",
        accentColor = "#C6CA4C",
    )
}
