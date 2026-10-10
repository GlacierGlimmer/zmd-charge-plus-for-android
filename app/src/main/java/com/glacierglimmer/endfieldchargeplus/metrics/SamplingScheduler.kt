package com.glacierglimmer.endfieldchargeplus.metrics

import android.os.SystemClock
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The single sampling authority of the Android edition.
 *
 * One coroutine runs per [SamplingTier] and calls every registered [MetricCollector] whose tier is
 * due, merging the results into one [MetricSnapshot]. No collector owns a timer, which is what
 * keeps the power profile predictable on a phone:
 *
 *  * the cadence comes from `AppConfig.android.{fast,normal,slow,idle}RefreshMs`;
 *  * [MetricDemand] throttles a tier that is not needed right now (HUD hidden, screen off, or no
 *    output at all) without ever stopping the other tiers;
 *  * a collector never runs concurrently with itself (per-collector [Mutex]);
 *  * a failing collector is logged with [AppLog.w] and cannot stop the others;
 *  * `requestImmediate()` wakes all tiers without disturbing their aligned ticks.
 */
class SamplingScheduler(
    private val configProvider: () -> AppConfig,
    private val onSnapshot: ((MetricSnapshot) -> Unit)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _snapshot = MutableStateFlow(MetricSnapshot.Empty)

    /** The merged variables of every tier, updated after each successful tier pass. */
    val snapshot: StateFlow<MetricSnapshot> = _snapshot.asStateFlow()

    private val registrations = CopyOnWriteArrayList<Registration>()

    private val merged = LinkedHashMap<String, MetricValue>()
    private val valuesByCollector = LinkedHashMap<String, Map<String, MetricValue>>()

    private val mergeLock = Mutex()

    private val demand = MutableStateFlow(MetricDemand.Idle)

    private val wakes = ConcurrentHashMap<SamplingTier, Channel<Boolean>>()

    /** Interrupt an old delay without taking an extra sample when cadence changes. */
    fun configurationChanged() {
        for (tier in SamplingTier.entries) wakes[tier]?.trySend(false)
    }

    @Volatile
    private var running = false

    private var scope: CoroutineScope? = null

    /** Registers a collector; call before [start] (later registrations are picked up as well). */
    fun register(collector: MetricCollector) {
        if (registrations.none { it.collector === collector }) {
            registrations.add(Registration(collector))
            AppLog.d(TAG, "collector registered: ${collector.id} (${collector.tier})")
        }
    }

    /** Collectors currently registered, for the diagnostics page. */
    fun registeredIds(): List<String> = registrations.map { it.collector.id }

    /**
     * Applies the current HUD demand.
     *
     * Tiers whose effective interval *decreased* are woken immediately so that showing the HUD
     * never waits for the previous (long) idle delay to expire.
     */
    fun setDemand(value: MetricDemand) {
        val previous = demand.value
        if (previous == value) return
        demand.value = value
        if (!running) return
        val settings = configProvider().android
        for (tier in SamplingTier.entries) {
            val before = DemandThrottle.effectiveIntervalMs(tier, settings, previous)
            val after = DemandThrottle.effectiveIntervalMs(tier, settings, value)
            if (after < before) wakes[tier]?.trySend(true)
        }
    }

    /** Current demand; exposed for tests and diagnostics. */
    fun currentDemand(): MetricDemand = demand.value

    /** Starts one coroutine per tier. Idempotent. */
    fun start() {
        if (running) return
        running = true
        val job = SupervisorJob()
        val schedulerScope = CoroutineScope(job + dispatcher)
        scope = schedulerScope
        for (tier in SamplingTier.entries) {
            val wake = Channel<Boolean>(Channel.CONFLATED)
            wakes[tier] = wake
            schedulerScope.launch { runTier(tier, wake) }
        }
        AppLog.i(TAG, "sampling started (tiers=${SamplingTier.entries.joinToString()})")
        requestImmediate()
    }

    /** Stops every tier coroutine. Idempotent. */
    fun stop() {
        if (!running && scope == null) return
        running = false
        wakes.clear()
        scope?.cancel()
        scope = null
        AppLog.i(TAG, "sampling stopped")
    }

    /** Wakes every tier now; the aligned tick schedule is preserved. */
    fun requestImmediate() {
        for (tier in SamplingTier.entries) {
            wakes[tier]?.trySend(true)
        }
    }

    private suspend fun runTier(tier: SamplingTier, wake: Channel<Boolean>) {
        val tick = TierTick()
        while (running && currentCoroutineContext().isActive) {
            val settings = configProvider().android
            val intervalMs = DemandThrottle.effectiveIntervalMs(tier, settings, demand.value)
            val delayMs = tick.delayUntilNext(intervalMs)
            if (delayMs > 0L) {
                // A requested immediate pass must not consume the aligned tick.
                val woke = withTimeoutOrNull(delayMs) { wake.receive() }
                if (!running) break
                if (woke != null) {
                    if (!woke) continue
                    collectTier(tier)
                    continue
                }
            }
            tick.consume(intervalMs)
            collectTier(tier)
        }
    }

    private suspend fun collectTier(tier: SamplingTier) {
        val due = registrations.filter { it.collector.tier == tier }
        if (due.isEmpty()) return
        val updated = LinkedHashMap<String, Map<String, MetricValue>>()
        for (registration in due) {
            val result = LinkedHashMap<String, MetricValue>()
            // runCatching isolates one broken collector from all the others; a coroutine
            // cancellation is rethrown so stop()/scope cancellation still works.
            val failure = runCatching {
                registration.mutex.withLock { registration.collector.collect(result) }
            }.exceptionOrNull()
            if (failure != null) {
                if (failure is CancellationException) throw failure
                AppLog.w(
                    TAG,
                    "collector ${registration.collector.id} failed; other collectors continue",
                    failure,
                )
                mergeLock.withLock {
                    valuesByCollector[registration.collector.id].orEmpty().keys.forEach { key ->
                        result[key] = MetricValue.Unavailable(UnavailableReason.NOT_AVAILABLE_ON_DEVICE, "collector ${registration.collector.id} failed")
                    }
                }
            }
            updated[registration.collector.id] = result.toMap()
        }
        mergeLock.withLock {
            valuesByCollector.putAll(updated)
            merged.clear()
            valuesByCollector.values.forEach { merged.putAll(it) }
            val snapshot = MetricSnapshot(merged.toMap(), System.currentTimeMillis())
            _snapshot.value = snapshot
            onSnapshot?.invoke(snapshot)
        }
    }

    private class Registration(val collector: MetricCollector) {
        val mutex = Mutex()
    }

    private companion object {
        const val TAG = "SamplingScheduler"
    }
}

/**
 * Effective cadence for one tier under the current [MetricDemand].
 *
 * The rules are additive (the slowest applicable cadence wins) so a hidden HUD on a sleeping
 * screen can never accidentally sample faster than the fastest throttle allows:
 *  * HUD hidden + `throttleWhenHidden` → at least [AndroidSettings.slowRefreshMs];
 *  * screen off + `throttleWhenScreenOff` → at least [AndroidSettings.screenOffRefreshMs];
 *  * no output and no foreground UI → the slowest cadence ([AndroidSettings.idleRefreshMs]);
 *  * [MetricDemand.userPaused] → the slowest cadence.
 */
internal object DemandThrottle {

    fun configuredIntervalMs(tier: SamplingTier, settings: AndroidSettings): Long = when (tier) {
        SamplingTier.FAST -> settings.fastRefreshMs
        SamplingTier.NORMAL -> settings.normalRefreshMs
        SamplingTier.SLOW -> settings.slowRefreshMs
        SamplingTier.IDLE -> settings.idleRefreshMs
    }.coerceAtLeast(TierTick.MIN_INTERVAL_MS)

    fun effectiveIntervalMs(tier: SamplingTier, settings: AndroidSettings, demand: MetricDemand): Long {
        var interval = configuredIntervalMs(tier, settings)
        if (!demand.hudVisible && settings.throttleWhenHidden) {
            interval = maxOf(interval, settings.slowRefreshMs)
        }
        if (!demand.screenOn && settings.throttleWhenScreenOff) {
            interval = maxOf(interval, settings.screenOffRefreshMs)
        }
        if (!demand.outputActive && !demand.foregroundUi && !demand.hudVisible) {
            interval = maxOf(interval, settings.idleRefreshMs)
        }
        if (demand.userPaused) {
            interval = maxOf(interval, settings.idleRefreshMs)
        }
        return interval.coerceAtLeast(TierTick.MIN_INTERVAL_MS)
    }
}

/**
 * Aligned tick generator for one tier.
 *
 * Ticks stay on a fixed grid (`base + k * interval`) instead of drifting with the execution time,
 * which matters for wall-clock-accurate profiles such as `time.day-progress`. [delayUntilNext]
 * only *peeks* at the next grid point so that an `requestImmediate()` pass never consumes a
 * scheduled tick; [consume] commits the tick that actually fired. The grid is re-based when the
 * configured interval changes.
 */
internal class TierTick(private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var baseMs: Long = -1L

    private var nextAtMs: Long = -1L

    private var lastIntervalMs: Long = -1L

    /** Milliseconds to wait until the next aligned tick, without consuming it. */
    fun delayUntilNext(intervalMs: Long): Long {
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val now = nowMs()
        if (baseMs < 0L || interval != lastIntervalMs) {
            baseMs = now
            nextAtMs = now + interval
            lastIntervalMs = interval
        } else if (nextAtMs <= now) {
            val missed = (now - nextAtMs) / interval + 1
            nextAtMs += missed * interval
        }
        return (nextAtMs - now).coerceAtLeast(0L)
    }

    /** Marks the current tick as fired and schedules the following grid point. */
    fun consume(intervalMs: Long) {
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val now = nowMs()
        if (baseMs < 0L || interval != lastIntervalMs) {
            baseMs = now
            nextAtMs = now + interval
            lastIntervalMs = interval
            return
        }
        val steps = (now - baseMs) / interval + 1
        nextAtMs = baseMs + steps * interval
        if (nextAtMs <= now) nextAtMs = now + interval
    }

    companion object {
        /** Hard floor so a bad configuration can never turn the sampler into a busy loop. */
        const val MIN_INTERVAL_MS = 100L
    }
}
