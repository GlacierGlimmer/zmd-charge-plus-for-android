package com.glacierglimmer.endfieldchargeplus.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the icon catalog contract: all 35 desktop identifiers are present with geometry, lookups
 * are case-insensitive, and an unknown name is reported as a placeholder instead of throwing.
 *
 * The geometry parser itself is exercised on-device (it produces `android.graphics.Path`, which the
 * JVM unit-test stubs do not implement); this test covers the catalog data and the resolution rules.
 */
class HudIconCatalogTest {

    private val requiredNames = listOf(
        "bolt", "battery", "cpu", "memory", "gpu", "network", "disk", "clock",
        "api", "signal", "gauge",
    )

    @Test
    fun `the catalog exports exactly the 35 desktop identifiers`() {
        assertEquals(35, HudIconCatalog.names.size)
        assertEquals(HudIconCatalog.names.size, HudIconCatalog.names.toSet().size)
        assertEquals(HudIconCatalog.names.toSet(), HudIconCatalog.geometrySources.keys)
    }

    @Test
    fun `every identifier used by the built-in schemes and the desktop catalog is present`() {
        for (name in requiredNames) {
            assertTrue("missing icon: $name", HudIconCatalog.isKnown(name))
            assertFalse("missing geometry: $name", HudIconCatalog.geometrySource(name).isNullOrBlank())
        }
    }

    @Test
    fun `lookups ignore case and surrounding whitespace`() {
        assertTrue(HudIconCatalog.isKnown("  BoLt "))
        assertEquals(HudIconCatalog.geometrySource("cpu"), HudIconCatalog.geometrySource(" CPU"))
    }

    @Test
    fun `unknown and blank names resolve to the neutral placeholder`() {
        assertFalse(HudIconCatalog.isKnown("not-an-icon"))
        assertFalse(HudIconCatalog.isKnown(""))
        assertFalse(HudIconCatalog.isKnown(null))
        assertTrue(HudIconCatalog.isPlaceholder(""))
        assertNull(HudIconCatalog.geometrySource("not-an-icon"))
    }

    @Test
    fun `every geometry is a well formed path source`() {
        for ((name, source) in HudIconCatalog.geometrySources) {
            assertTrue("empty geometry for $name", source.isNotBlank())
            assertTrue("geometry of $name does not start with a moveto", source.startsWith("M"))
            // Every glyph has at least one shaping command; some (clock, timer) end without a close.
            assertTrue(
                "geometry of $name has no drawing command",
                source.contains(" L") || source.contains(" A") || source.contains(" C"),
            )
        }
    }
}
