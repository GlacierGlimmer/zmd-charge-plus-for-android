package com.glacierglimmer.endfieldchargeplus.permission

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionStatusMonitorTest {
    @Test fun `first Home waits for native eligibility instead of publishing the stale unsupported cache`() = runTest {
        var cachedEligible = false
        var reads = 0
        val monitor = PermissionStatusMonitor(backgroundScope,
            refreshEligibility = { delay(100); cachedEligible = true },
            readPermissions = { reads++; listOf(state(cachedEligible)) },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(monitor.permissions.value.isEmpty())
        assertTrue(monitor.loading.value)
        runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(100); runCurrent()
        assertTrue(monitor.permissions.value.single().granted)
        assertFalse(monitor.loading.value)
        assertEquals(1, reads)
    }

    @Test fun `returning from system settings refreshes a revoked native permission without opening Display`() = runTest {
        var actualEligible = true
        var cachedEligible = false
        val monitor = PermissionStatusMonitor(backgroundScope,
            refreshEligibility = { cachedEligible = actualEligible },
            readPermissions = { listOf(state(cachedEligible)) },
            dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(monitor.permissions.value.single().granted)
        actualEligible = false
        monitor.refresh(); runCurrent()
        assertFalse(monitor.permissions.value.single().granted)
        actualEligible = true
        monitor.refresh(); runCurrent()
        assertTrue(monitor.permissions.value.single().granted)
    }

    @Test fun `resume events during initial detection do not create competing permission reads`() = runTest {
        var refreshes = 0
        var reads = 0
        val monitor = PermissionStatusMonitor(backgroundScope,
            refreshEligibility = { refreshes++; delay(100) },
            readPermissions = { reads++; listOf(state(true)) },
            dispatcher = StandardTestDispatcher(testScheduler))
        repeat(50) { monitor.refresh() }
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(1, reads)
        assertTrue(monitor.permissions.value.single().granted)
    }

    private fun state(granted: Boolean) = PermissionState(EcpPermission.LIVE_UPDATE, granted, "native")
}
