package com.glacierglimmer.endfieldchargeplus.core.metrics
import org.junit.Assert.*
import org.junit.Test
class WarningIncidentGateTest {
    @Test fun `a warmup is allowed then the incident appears only once`() {
        val gate=WarningIncidentGate()
        assertFalse(gate.shouldPresent("profile",listOf("fps"),0,true))
        assertFalse(gate.shouldPresent("profile",listOf("fps"),4900,true))
        assertTrue(gate.shouldPresent("profile",listOf("fps"),5000,true))
        assertFalse(gate.shouldPresent("profile",listOf("fps"),7000,true))
        gate.shouldPresent("profile",emptyList(),7100,true)
        assertFalse(gate.shouldPresent("profile",listOf("fps"),7200,true))
        assertTrue(gate.shouldPresent("profile",listOf("fps"),12200,true))
    }
    @Test fun `busy or sleeping presentation is deferred without discarding the error`() {
        val gate=WarningIncidentGate()
        gate.shouldPresent("profile",listOf("fps"),0,false)
        assertFalse(gate.shouldPresent("profile",listOf("fps"),6000,false))
        assertTrue(gate.shouldPresent("profile",listOf("fps"),7000,true))
    }
    @Test fun `carousel schemes keep separate incidents and recovery resets only its own scheme`() {
        val gate=WarningIncidentGate(0)
        assertTrue(gate.shouldPresent("one",listOf("fps"),0,true))
        assertTrue(gate.shouldPresent("two",listOf("memory"),0,true))
        gate.shouldPresent("one",emptyList(),1,true)
        assertFalse(gate.shouldPresent("two",listOf("memory"),2,true))
        assertTrue(gate.shouldPresent("one",listOf("fps"),2,true))
    }
}