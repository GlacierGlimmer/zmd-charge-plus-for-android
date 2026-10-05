package com.glacierglimmer.endfieldchargeplus.ui.screens.datasources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHttpSource
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HttpFieldMapping
import com.glacierglimmer.endfieldchargeplus.data.SecretStore
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import com.glacierglimmer.endfieldchargeplus.ui.state.DeepSeekTest
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Feedback of a data source action. */
enum class DataSourceMessage {
    API_KEY_SAVED,
    API_KEY_REMOVED,
    SECRET_STORE_UNAVAILABLE,
    SOURCE_ADDED,
    SOURCE_REMOVED,
}

/**
 * Data sources page state.
 *
 * The DeepSeek key lives in the encrypted secret store (never in the plain configuration), the HTTP
 * sources live in the configuration, and the probe target lives on the active scheme — exactly like
 * the desktop editions.
 */
class DataSourcesViewModel(private val container: EcpContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config

    val snapshot: StateFlow<MetricSnapshot> = container.metricRepository.snapshot

    val capabilities: StateFlow<HardwareCapabilities> = container.metricRepository.capabilities

    private val _apiKey = MutableStateFlow(container.secretStore.get(SecretStore.KEY_DEEPSEEK_API_KEY).orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _secretStoreAvailable = MutableStateFlow(runCatching { container.secretStore.isAvailable() }.getOrDefault(false))
    val secretStoreAvailable: StateFlow<Boolean> = _secretStoreAvailable.asStateFlow()

    private val _testOutcome = MutableStateFlow<DeepSeekTest.Outcome?>(null)
    val testOutcome: StateFlow<DeepSeekTest.Outcome?> = _testOutcome.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _message = MutableStateFlow<DataSourceMessage?>(null)
    val message: StateFlow<DataSourceMessage?> = _message.asStateFlow()

    init {
        _apiKey.value = runCatching {
            container.secretStore.get(SecretStore.KEY_DEEPSEEK_API_KEY)
        }.getOrNull().orEmpty()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeTestOutcome() {
        _testOutcome.value = null
    }

    /** Reads the key from the encrypted store again (for example after returning from a backup). */
    fun reloadApiKey() {
        _apiKey.value = runCatching {
            container.secretStore.get(SecretStore.KEY_DEEPSEEK_API_KEY)
        }.getOrNull().orEmpty()
        _secretStoreAvailable.value = runCatching { container.secretStore.isAvailable() }.getOrDefault(false)
    }

    /** Stores the key encrypted and registers it with the log redactor. */
    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        val stored = runCatching {
            if (trimmed.isEmpty()) {
                container.secretStore.remove(SecretStore.KEY_DEEPSEEK_API_KEY)
            } else {
                container.secretStore.put(SecretStore.KEY_DEEPSEEK_API_KEY, trimmed)
                AppLog.registerSecret(trimmed)
            }
            true
        }.getOrDefault(false)
        if (!stored) {
            _message.value = DataSourceMessage.SECRET_STORE_UNAVAILABLE
            return
        }
        _apiKey.value = if (trimmed.isEmpty()) "" else runCatching {
            container.secretStore.get(SecretStore.KEY_DEEPSEEK_API_KEY)
        }.getOrNull().orEmpty()
        _message.value = if (trimmed.isEmpty()) {
            DataSourceMessage.API_KEY_REMOVED
        } else {
            DataSourceMessage.API_KEY_SAVED
        }
    }

    /** Verifies the credential against the configured endpoint. */
    fun testDeepSeek() {
        val key = _apiKey.value
        val baseUrl = config.value.android.deepSeekBaseUrl
        viewModelScope.launch {
            _testing.value = true
            _testOutcome.value = runCatching { DeepSeekTest.test(key, baseUrl) }
                .getOrElse { DeepSeekTest.Outcome(reachable = false, note = it.javaClass.simpleName) }
            _testing.value = false
        }
    }

    // ------------------------------------------------------------------ sampler

    fun refreshNow() = container.metricRepository.refreshNow()

    /** True when the sampler currently reports a real reading for [variable]. */
    fun isAvailable(variable: String): Boolean = snapshot.value.isAvailable(variable)

    // ------------------------------------------------------------------ probe

    fun setProbeEnabled(enabled: Boolean) = update { config ->
        config.copy(android = config.android.copy(probeEnabled = enabled))
    }

    fun setProbeIntervalSeconds(seconds: Int) = update { config ->
        config.copy(android = config.android.copy(probeIntervalSeconds = seconds.coerceIn(1, 3600)))
    }

    fun setProbeTimeoutMs(milliseconds: Int) = update { config ->
        config.copy(android = config.android.copy(probeTimeoutMs = milliseconds.coerceIn(100, 30_000)))
    }

    fun setProbeSampleWindow(samples: Int) = update { config ->
        config.copy(android = config.android.copy(probeSampleWindow = samples.coerceIn(3, 120)))
    }

    /** The probe target/protocol/port belong to the active scheme (cross-platform contract). */
    fun updateActiveProfileProbe(
        target: String? = null,
        protocol: String? = null,
        port: Int? = null,
    ) = updateActiveProfile { profile ->
        profile.copy(
            pingTarget = target ?: profile.pingTarget,
            probeProtocol = protocol ?: profile.probeProtocol,
            probePort = port ?: profile.probePort,
        )
    }

    // ------------------------------------------------------------------ DeepSeek

    fun setDeepSeekRefreshSeconds(seconds: Int) = update { config ->
        config.copy(android = config.android.copy(deepSeekRefreshSeconds = seconds.coerceIn(30, 86_400)))
    }

    fun setDeepSeekBaseUrl(url: String) = update { config ->
        config.copy(android = config.android.copy(deepSeekBaseUrl = url.trim()))
    }

    fun setDeepSeekPeakWindows(windows: String) = update { config ->
        config.copy(customHud = config.customHud.copy(deepSeekPeakWindows = windows.trim()))
    }

    // ------------------------------------------------------------------ HTTP/JSON sources

    fun addHttpSource() = update { config ->
        val index = config.customHud.httpSources.size + 1
        val source = CustomHttpSource(
            name = "source$index",
            enabled = true,
            url = "",
            refreshSeconds = 60,
            headers = emptyMap(),
            fields = listOf(HttpFieldMapping(variable = "value", jsonPath = "")),
        )
        config.copy(customHud = config.customHud.copy(httpSources = config.customHud.httpSources + source))
    }.also { _message.value = DataSourceMessage.SOURCE_ADDED }

    fun removeHttpSource(index: Int) = update { config ->
        val sources = config.customHud.httpSources.toMutableList()
        if (index in sources.indices) sources.removeAt(index)
        config.copy(customHud = config.customHud.copy(httpSources = sources))
    }.also { _message.value = DataSourceMessage.SOURCE_REMOVED }

    fun updateHttpSource(index: Int, transform: (CustomHttpSource) -> CustomHttpSource) = update { config ->
        val sources = config.customHud.httpSources.mapIndexed { position, source ->
            if (position == index) transform(source) else source
        }
        config.copy(customHud = config.customHud.copy(httpSources = sources))
    }

    fun activeProfile(): HudProfile? = config.value.customHud.activeProfile()

    private fun updateActiveProfile(transform: (HudProfile) -> HudProfile) {
        val profile = activeProfile() ?: return
        update { config -> ProfileEdits.updateProfile(config, profile.id, transform) }
    }

    private fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DataSourcesViewModel(container) }
        }
    }
}
