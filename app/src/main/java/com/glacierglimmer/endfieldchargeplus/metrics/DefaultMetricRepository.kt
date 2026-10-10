package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.hardware.HardwareCapabilityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The application's single [MetricRepository].
 *
 * Construction is deliberately cheap and side-effect free: no timer starts and no hardware is
 * probed until [start] is called. Collectors are wired here (system collectors plus the
 * probe/HTTP/DeepSeek collectors supplied by the DI container through [externalCollectors]) and
 * handed to [SamplingScheduler], which owns the only clock in the data layer.
 *
 * The active profile is read through an environment view that prefers [setActiveProfile] when the
 * caller has a fresher value than the configuration store.
 */
class DefaultMetricRepository(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val environment: MetricEnvironment,
    externalCollectors: List<MetricCollector> = emptyList(),
    private val kernelReader: KernelReader = FileKernelReader,
) : MetricRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val profileOverride = MutableStateFlow<HudProfile?>(null)

    private val environmentView = object : MetricEnvironment {
        override fun config(): AppConfig = configRepository.config.value

        override fun activeProfile(): HudProfile? = profileOverride.value ?: environment.activeProfile()
    }

    private val _snapshot = MutableStateFlow(MetricSnapshot.Empty)

    override val snapshot: StateFlow<MetricSnapshot> = _snapshot.asStateFlow()

    private val _capabilities = MutableStateFlow(HardwareCapabilities.notScanned())

    override val capabilities: StateFlow<HardwareCapabilities> = _capabilities.asStateFlow()

    private val _running = MutableStateFlow(false)

    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val detector = HardwareCapabilityDetector(context, kernelReader)

    private val scheduler = SamplingScheduler(
        configProvider = { configRepository.config.value },
        onSnapshot = { merged -> _snapshot.value = merged },
    )

    private val collectors: List<MetricCollector> = buildList {
        add(MemoryCollector(context, environmentView, kernelReader))
        add(BatteryCollector(context, environmentView))
        add(NetworkCollector(context, environmentView))
        add(StorageCollector(context, environmentView))
        add(TimeCollector(context, environmentView))
        add(CpuCollector(context, environmentView, kernelReader))
        add(GpuCollector(context, environmentView, kernelReader))
        add(DeviceCollector(context, environmentView))
        addAll(
            runCatching { externalCollectors.toList() }.getOrElse { error ->
                AppLog.w(TAG, "external collectors could not be registered; continuing with system metrics", error)
                emptyList()
            },
        )
    }

    private var lifecycleJob: Job? = null

    init {
        collectors.forEach { collector ->
            runCatching { scheduler.register(collector) }.onFailure { error ->
                AppLog.w(TAG, "collector ${collector.id} could not be registered", error)
            }
        }
        AppLog.d(TAG, "repository created with collectors=${scheduler.registeredIds()}")
    }

    @Synchronized override fun start() {
        if (_running.value) return
        _running.value = true
        scheduler.start()
        if (lifecycleJob == null) {
            lifecycleJob = scope.launch {
                // Re-read the cadence whenever the configuration changes; the scheduler reads the
                // same provider on every tick, so a new interval takes effect immediately.
                configRepository.config.collect { config ->
                    AppLog.d(
                        TAG,
                        "config cadence: fast=${config.android.fastRefreshMs} normal=${config.android.normalRefreshMs} " +
                            "slow=${config.android.slowRefreshMs} idle=${config.android.idleRefreshMs} " +
                            "throttleHidden=${config.android.throttleWhenHidden} " +
                            "throttleScreenOff=${config.android.throttleWhenScreenOff}",
                    )
                    scheduler.configurationChanged()
                }
            }
            scope.launch { detectCapabilities("start") }
        }
        AppLog.i(TAG, "metric repository started")
    }

    @Synchronized override fun stop() {
        if (!_running.value && lifecycleJob == null) return
        _running.value = false
        scheduler.stop()
        lifecycleJob?.cancel()
        lifecycleJob = null
        AppLog.i(TAG, "metric repository stopped")
    }

    override fun refreshNow() {
        scheduler.requestImmediate()
    }

    override fun setDemand(demand: MetricDemand) {
        scheduler.setDemand(demand)
    }

    override fun setActiveProfile(profile: HudProfile?) {
        updateSamplingProfile(profileOverride, profile, scheduler::requestImmediate)
    }

    override suspend fun refreshCapabilities(): HardwareCapabilities = detectCapabilities("manual")

    private suspend fun detectCapabilities(reason: String): HardwareCapabilities {
        val report = withContext(Dispatchers.IO) {
            runCatching { detector.detect() }.getOrElse { error ->
                AppLog.w(TAG, "hardware capability detection failed ($reason)", error)
                _capabilities.value
            }
        }
        _capabilities.value = report
        AppLog.i(
            TAG,
            "hardware capabilities ($reason): cpu=${report.cpuTotalUsage.supported} " +
                "cpuFreq=${report.cpuFrequency.supported} cpuTemp=${report.cpuTemperature.supported} " +
                "gpu=${report.gpuUsage.supported} batteryCurrent=${report.batteryCurrent.supported} " +
                "storage=${report.storage.supported} traffic=${report.networkTraffic.supported} " +
                "wifiSsid=${report.wifiSsid.supported} icmp=${report.probeIcmp.supported}",
        )
        return report
    }

    private companion object {
        const val TAG = "MetricRepository"
    }
}

/** Rendering an existing profile must not feed another immediate sample back into the renderer. */
internal fun updateSamplingProfile(
    current: MutableStateFlow<HudProfile?>,
    profile: HudProfile?,
    requestImmediate: () -> Unit,
) {
    if (current.value == profile) return
    current.value = profile
    requestImmediate()
}
