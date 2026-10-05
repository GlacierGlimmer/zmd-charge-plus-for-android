package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.ui.state.UiFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field parsing/formatting helpers used across the settings screens. */
class UiFormattingTest {

    @Test
    fun numberTextNeverShowsTrailingZeros() {
        assertEquals("6", UiFormatting.numberText(6.0))
        assertEquals("0.275", UiFormatting.numberText(0.275))
        assertEquals("100", UiFormatting.numberText(100.0))
        assertEquals("0", UiFormatting.numberText(Double.NaN))
    }

    @Test
    fun percentTextRoundsToWholePercent() {
        assertEquals("80%", UiFormatting.percentText(0.8))
        assertEquals("100%", UiFormatting.percentText(1.0))
        assertEquals("10%", UiFormatting.percentText(0.1))
    }

    @Test
    fun parsingToleratesCommasAndSpaces() {
        assertEquals(1.5, UiFormatting.parseDouble(" 1,5 ")!!, 1e-9)
        assertEquals(42, UiFormatting.parseInt(" 42 ")!!)
        assertNull(UiFormatting.parseDouble("abc"))
        assertNull(UiFormatting.parseInt(""))
    }

    @Test
    fun clampsIntoRangeWithAFallback() {
        assertEquals(10.0, UiFormatting.clampDouble("99", 0.0, 10.0, 5.0), 1e-9)
        assertEquals(5.0, UiFormatting.clampDouble("not-a-number", 0.0, 10.0, 5.0), 1e-9)
        assertEquals(1, UiFormatting.clampInt("-4", 1, 65535, 443))
        assertEquals(443, UiFormatting.clampInt("x", 1, 65535, 443))
    }

    @Test
    fun colourValidationAcceptsRgbAndArgb() {
        assertTrue(UiFormatting.isColor("#C6CA4C"))
        assertTrue(UiFormatting.isColor("#FFC6CA4C"))
        assertFalse(UiFormatting.isColor("C6CA4C"))
        assertFalse(UiFormatting.isColor("#GGGGGG"))
        assertFalse(UiFormatting.isColor("#12345"))
        assertEquals(0xFFC6CA4CL, UiFormatting.parseColor("#FFC6CA4C"))
    }

    @Test
    fun byteRatesUseDecimalSiUnits() {
        assertEquals("0.0 KB/s", UiFormatting.bytesPerSecondText(0.0))
        assertEquals("1.0 KB/s", UiFormatting.bytesPerSecondText(1_000.0))
        assertEquals("999.0 KB/s", UiFormatting.bytesPerSecondText(999_000.0))
        assertEquals("1.0 MB/s", UiFormatting.bytesPerSecondText(1_000_000.0))
        assertEquals("2.5 MB/s", UiFormatting.bytesPerSecondText(2_500_000.0))
        assertEquals("—", UiFormatting.bytesPerSecondText(null))
        assertEquals("—", UiFormatting.bytesPerSecondText(-1.0))
    }
}
