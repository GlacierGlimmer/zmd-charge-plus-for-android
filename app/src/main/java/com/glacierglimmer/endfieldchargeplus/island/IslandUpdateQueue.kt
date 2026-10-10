package com.glacierglimmer.endfieldchargeplus.island

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One pending frame, published off the UI thread at the backend's actual supported cadence. */
internal class IslandUpdateQueue(
    scope: CoroutineScope,
    private val intervalMs: Long,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val publish: (IslandContent) -> Unit,
) {
    private val frames = Channel<IslandContent>(Channel.CONFLATED)
    private val job = scope.launch(dispatcher) {
        var previous: IslandContent? = null
        var lastSentAt: Long? = null
        for (frame in frames) {
            if (frame == previous) continue
            lastSentAt?.let { delay((intervalMs - (nowMs() - it)).coerceAtLeast(0)) }
            val latest = frames.tryReceive().getOrNull() ?: frame
            if (!isActive) break
            if (latest == previous) continue
            publish(latest)
            previous = latest
            lastSentAt = nowMs()
        }
    }

    fun offer(frame: IslandContent) { frames.trySend(frame) }
    fun stop() { frames.close(); job.cancel() }
}
