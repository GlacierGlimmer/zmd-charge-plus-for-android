package com.glacierglimmer.endfieldchargeplus.core.metrics

import org.junit.Assert.*
import org.junit.Test

class DataWarningPreferencesTest {
    @Test fun `only selected variables are silenced and the choice survives a new instance`() {
        var stored=emptySet<String>()
        val prefs=DataWarningPreferences({stored},{stored=it;true})
        assertTrue(prefs.ignore("first",listOf("frame.window.fps")))
        val restarted=DataWarningPreferences({stored},{stored=it;true})
        assertEquals(listOf("frame.display.fps"),restarted.filter("another",listOf("FRAME.WINDOW.FPS","frame.display.fps")))
    }
    @Test fun `a later selection merges with previously ignored variables`() {
        var stored=emptySet<String>()
        val prefs=DataWarningPreferences({stored},{stored=it;true})
        prefs.ignore("a",listOf("metric.one"));prefs.ignore("b",listOf("metric.two"))
        assertEquals(listOf("metric.three"),prefs.filter("c",listOf("metric.one","metric.two","metric.three")))
    }
    @Test fun `one invalid expression does not silence a different profile or expression`() {
        var stored=emptySet<String>()
        val prefs=DataWarningPreferences({stored},{stored=it;true})
        prefs.ignore("a",listOf("= 1 / 0"))
        assertTrue(prefs.filter("a",listOf("= 1 / 0")).isEmpty())
        assertEquals(listOf("= 1 / 0","= 2 / 0"),prefs.filter("b",listOf("= 1 / 0","= 2 / 0")))
        assertEquals(listOf("= 2 / 0"),prefs.filter("a",listOf("= 1 / 0","= 2 / 0")))
    }
    @Test fun `a failed persistent write does not silence the variable`() {
        val prefs=DataWarningPreferences({emptySet()},{false})
        assertFalse(prefs.ignore("a",listOf("metric.one")))
        assertEquals(listOf("metric.one"),prefs.filter("a",listOf("metric.one")))
    }
    @Test fun `variables arriving after the selected notice remain eligible`() {
        var stored=emptySet<String>()
        val prefs=DataWarningPreferences({stored},{stored=it;true})
        val displayed=listOf("metric.one")
        prefs.ignore("a",displayed)
        assertEquals(listOf("metric.new"),prefs.filter("a",displayed+"metric.new"))
    }
}
