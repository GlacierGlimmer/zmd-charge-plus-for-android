package com.glacierglimmer.endfieldchargeplus.island

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IslandUpdateQueueTest {
    @Test fun `rapid changes keep only the newest frame and obey the five second limit`() = runTest {
        val sent = mutableListOf<Pair<Long, IslandContent>>()
        val queue = IslandUpdateQueue(backgroundScope, 5_000, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler)) {
            sent += testScheduler.currentTime to it
        }
        queue.offer(IslandContent(title = "Memory", subtitle = "initial")); runCurrent()
        assertEquals(1, sent.size)
        repeat(10_000) { queue.offer(IslandContent(title = "Memory", subtitle = "$it")) }
        runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 5_000L), sent.map { it.first })
        assertEquals("9999", sent.last().second.subtitle)
        queue.stop()
    }

    @Test fun `identical readings do not repost and stopping drops pending output`() = runTest {
        val sent = mutableListOf<IslandContent>()
        val queue = IslandUpdateQueue(backgroundScope, 5_000, { testScheduler.currentTime }, StandardTestDispatcher(testScheduler)) { sent += it }
        val frame = IslandContent(title = "Battery", subtitle = "100%")
        queue.offer(frame); runCurrent()
        repeat(1_000) { queue.offer(frame.copy()) }; runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, sent.size)
        queue.offer(frame.copy(subtitle = "99%")); runCurrent()
        queue.offer(frame.copy(subtitle = "98%")); runCurrent()
        queue.stop(); advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("100%", "99%"), sent.map { it.subtitle })
    }
}
