package com.glacierglimmer.endfieldchargeplus.di

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.data.CapabilityProfileFilter
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import com.glacierglimmer.endfieldchargeplus.data.DataStoreConfigRepository
import com.glacierglimmer.endfieldchargeplus.data.EncryptedSecretStore
import com.glacierglimmer.endfieldchargeplus.data.SecretStore
import com.glacierglimmer.endfieldchargeplus.deepseek.DeepSeekCollector
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.hardware.HardwareCapabilityDetector
import com.glacierglimmer.endfieldchargeplus.hud.HudStateBuilder
import com.glacierglimmer.endfieldchargeplus.hud.HudStateBuilderImpl
import com.glacierglimmer.endfieldchargeplus.island.IslandNotificationHost
import com.glacierglimmer.endfieldchargeplus.island.IslandProviderRegistry
import com.glacierglimmer.endfieldchargeplus.metrics.DefaultMetricRepository
import com.glacierglimmer.endfieldchargeplus.metrics.MetricCollector
import com.glacierglimmer.endfieldchargeplus.metrics.MetricEnvironment
import com.glacierglimmer.endfieldchargeplus.metrics.MetricRepository
import com.glacierglimmer.endfieldchargeplus.network.DefaultHttpJsonClient
import com.glacierglimmer.endfieldchargeplus.network.DefaultNetworkProbe
import com.glacierglimmer.endfieldchargeplus.network.HttpJsonCollector
import com.glacierglimmer.endfieldchargeplus.network.NetworkProbe
import com.glacierglimmer.endfieldchargeplus.network.ProbeCollector
import com.glacierglimmer.endfieldchargeplus.overlay.DefaultOverlayController
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayController
import com.glacierglimmer.endfieldchargeplus.permission.PermissionManager
import com.glacierglimmer.endfieldchargeplus.permission.PermissionManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide service locator.
 *
 * The Android edition deliberately avoids a DI framework: the object graph is small, explicit and
 * fully visible here. This is also the single place that stitches the layers together, which keeps
 * the UI, the overlay, the island providers and the collectors independent of each other
 * (UI != data collection, overlay != island, config != Compose state).
 */
class EcpContainer private constructor(private val appContext: Context) {

    /** Application-scoped coroutines for work that must outlive an Activity. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val configRepository: ConfigRepository by lazy {
        DataStoreConfigRepository(appContext).apply {
            // The normalizer strips `{variable}` references this device cannot really produce
            // (for example gpu.* or cpu.temperature_c) using a real capability scan.
            capabilityVariableProvider = { unsupportedVariables }
        }
    }

    private val capabilityDetector: HardwareCapabilityDetector by lazy { HardwareCapabilityDetector(appContext) }

    /** Unsupported-variable patterns of this device, derived once from a real capability scan. */
    private val unsupportedVariables: Set<String> by lazy {
        runCatching { CapabilityProfileFilter.unsupportedVariables(capabilityDetector.detect()) }
            .onFailure { AppLog.w(TAG, "Hardware capability scan failed; keeping all profile variables", it) }
            .getOrDefault(emptySet())
    }

    val secretStore: SecretStore by lazy { EncryptedSecretStore(appContext) }

    val permissionManager: PermissionManager by lazy { PermissionManagerImpl(appContext) }

    private val networkProbe: NetworkProbe by lazy { DefaultNetworkProbe() }

    private val httpJsonClient: DefaultHttpJsonClient by lazy { DefaultHttpJsonClient() }

    private val metricEnvironment: MetricEnvironment by lazy {
        object : MetricEnvironment {
            override fun config() = configRepository.config.value
            override fun activeProfile() = configRepository.config.value.customHud.activeProfile()
        }
    }

    /** Collectors implemented outside the metrics package (probe, HTTP/JSON, DeepSeek). */
    private val externalCollectors: List<MetricCollector> by lazy {
        listOf(
            ProbeCollector(appContext, metricEnvironment, networkProbe),
            HttpJsonCollector(appContext, metricEnvironment, httpJsonClient),
            DeepSeekCollector(appContext, metricEnvironment, secretStore),
        )
    }

    val metricRepository: MetricRepository by lazy {
        DefaultMetricRepository(
            context = appContext,
            configRepository = configRepository,
            environment = metricEnvironment,
            externalCollectors = externalCollectors,
        )
    }

    val hudStateBuilder: HudStateBuilder by lazy { HudStateBuilderImpl() }

    val overlayController: OverlayController by lazy { DefaultOverlayController(appContext) }

    /**
     * The island registry needs a notification host; the foreground service supplies the real one
     * while it runs, and a disabled host is used otherwise.
     */
    val islandRegistry: IslandProviderRegistry by lazy {
        IslandProviderRegistry(
            context = appContext,
            configRepository = configRepository,
            notificationHost = IslandNotificationHost.Disabled,
        )
    }

    /** Starts the asynchronous part of the graph (config load, singleton warm-up). */
    fun warmUp() {
        applicationScope.launch { runCatching { configRepository.load() } }
    }

    companion object {
        private const val TAG = "EcpContainer"

        @Volatile
        private var instance: EcpContainer? = null

        fun of(context: Context): EcpContainer =
            instance ?: synchronized(this) {
                instance ?: EcpContainer(context.applicationContext).also { instance = it }
            }
    }
}
