package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.ui.state.ColorRuleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour rule text format is a cross-platform contract: it is the same line syntax the desktop
 * editor writes (`variable operator value => colour`), so an exported profile round-trips.
 */
class ColorRuleTextTest {

    @Test
    fun parsesEverySupportedOperator() {
        ColorRuleText.operators.forEach { operator ->
            val rules = ColorRuleText.parse("cpu.usage $operator 75.5 => #FF4D4F")
            assertEquals(1, rules.size)
            assertEquals("cpu.usage", rules[0].variable)
            assertEquals(operator, rules[0].operator)
            assertEquals(75.5, rules[0].value, 1e-9)
            assertEquals("#FF4D4F", rules[0].color)
        }
    }

    @Test
    fun skipsMalformedLinesLikeTheDesktopEditor() {
        val text = """
            cpu.usage >= 90 => #FF4D4F
            this line is not a rule
            memory.usage >= 80 => notacolor
            battery.percent <= 20 => #FF4D4FAA
        """.trimIndent()
        val rules = ColorRuleText.parse(text)
        assertEquals(2, rules.size)
        assertEquals("#FF4D4F", rules[0].color)
        assertEquals("#FF4D4FAA", rules[1].color)
        assertEquals(listOf(2, 3), ColorRuleText.invalidLines(text))
    }

    @Test
    fun supportsNegativeThresholdsAndWhitespace() {
        val rules = ColorRuleText.parse("   probe.jitter_ms   <   -1.25   =>   #00FF00  ")
        assertEquals(1, rules.size)
        assertEquals(-1.25, rules[0].value, 1e-9)
    }

    @Test
    fun formattingIsStableAndRoundTrips() {
        val rules = listOf(
            HudColorRule("cpu.usage", ">=", 90.0, "#FF4D4F"),
            HudColorRule("battery.percent", "<=", 20.0, "#FF4D4F"),
            HudColorRule("probe.loss_percent", ">=", 10.5, "#FFB84D"),
        )
        val text = ColorRuleText.format(rules)
        assertEquals(
            "cpu.usage >= 90 => #FF4D4F\nbattery.percent <= 20 => #FF4D4F\nprobe.loss_percent >= 10.5 => #FFB84D",
            text,
        )
        assertEquals(rules, ColorRuleText.parse(text))
    }

    @Test
    fun numberTextMatchesTheDesktopInvariantFormatting() {
        assertEquals("0", ColorRuleText.numberText(0.0))
        assertEquals("20", ColorRuleText.numberText(20.0))
        assertEquals("10.5", ColorRuleText.numberText(10.5))
        assertTrue(ColorRuleText.numberText(Double.NaN).isNotEmpty())
    }

    @Test
    fun blankInputProducesNothing() {
        assertTrue(ColorRuleText.parse("").isEmpty())
        assertTrue(ColorRuleText.parse(null).isEmpty())
        assertTrue(ColorRuleText.invalidLines("   ").isEmpty())
    }
}
