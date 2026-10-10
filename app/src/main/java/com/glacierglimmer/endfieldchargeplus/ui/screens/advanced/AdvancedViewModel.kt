package com.glacierglimmer.endfieldchargeplus.ui.screens.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.diagnostics.DiagnosticsReport
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import com.glacierglimmer.endfieldchargeplus.ui.state.CapabilityPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Feedback shown by the advanced page after an action. */
enum class AdvancedMessage {
    LOG_CLEARED,
    CAPABILITIES_REFRESHED,
    RESET_APPLIED,
    EXPORT_OK,
    EXPORT_FAILED,
    IMPORT_OK,
    IMPORT_FAILED,
}

/**
 * Advanced page state: sampling cadence, configuration transfer, the bounded diagnostics log, the
 * shareable report and the honest hardware capability table.
 */
class AdvancedViewModel(private val container: EcpContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config
    val rootStatus = container.rootAccess.status
    fun setUseRoot(enabled: Boolean) { viewModelScope.launch {
        container.configRepository.update { it.copy(android = it.android.copy(useRoot = enabled)) }
        if (!enabled) container.rootAccess.configure(false)
    } }
    fun retryRoot() { viewModelScope.launch {
        container.rootAccess.configure(config.value.android.useRoot)
        container.kernelReader.invalidate()
        container.metricRepository.refreshCapabilities()
        container.metricRepository.refreshNow()
    } }

    private val _capabilities = MutableStateFlow(container.metricRepository.capabilities.value)
    val capabilities: StateFlow<HardwareCapabilities> = _capabilities

    private val _reDetecting = MutableStateFlow(false)
    val reDetecting: StateFlow<Boolean> = _reDetecting.asStateFlow()

    private val _logLines = MutableStateFlow(readLogLines())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _message = MutableStateFlow<AdvancedMessage?>(null)
    val message: StateFlow<AdvancedMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            container.metricRepository.capabilities.collect { _capabilities.value = it }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------ sampling cadence

    fun setFastRefreshMs(value: Int) = update { it.copy(android = it.android.copy(fastRefreshMs = value.toLong().coerceIn(100, 60_000))) }
    fun setNormalRefreshMs(value: Int) = update { it.copy(android = it.android.copy(normalRefreshMs = value.toLong().coerceIn(200, 300_000))) }
    fun setSlowRefreshMs(value: Int) = update { it.copy(android = it.android.copy(slowRefreshMs = value.toLong().coerceIn(500, 600_000))) }
    fun setIdleRefreshMs(value: Int) = update { it.copy(android = it.android.copy(idleRefreshMs = value.toLong().coerceIn(1_000, 3_600_000))) }
    fun setScreenOffRefreshMs(value: Int) = update { it.copy(android = it.android.copy(screenOffRefreshMs = value.toLong().coerceIn(1_000, 3_600_000))) }

    fun setThrottleWhenHidden(enabled: Boolean) = update {
        it.copy(android = it.android.copy(throttleWhenHidden = enabled))
    }

    fun setThrottleWhenScreenOff(enabled: Boolean) = update {
        it.copy(android = it.android.copy(throttleWhenScreenOff = enabled))
    }

    fun setVerboseLogging(enabled: Boolean) {
        AppLog.setVerbose(enabled)
        update { it.copy(android = it.android.copy(verboseLogging = enabled)) }
        refreshLog()
    }

    // ------------------------------------------------------------------ log & report

    fun refreshLog() {
        _logLines.value = readLogLines()
    }

    fun clearLog() {
        AppLog.clear()
        _logLines.value = emptyList()
        _message.value = AdvancedMessage.LOG_CLEARED
    }

    /** Full text export of the bounded log. */
    fun logText(): String = AppLog.exportText()

    /** Diagnostics report, built by the canonical diagnostics package from the live state. */
    fun diagnosticsText(context: Context): String = DiagnosticsReport.build(
        context = context,
        config = config.value,
        capabilities = _capabilities.value,
        permissions = container.permissionManager.all(),
        extra = mapOf(
            "inMemoryLogEntries" to _logLines.value.size.toString(),
            "verboseLogging" to AppLog.isVerbose().toString(),
            "displayMode" to config.value.android.displayMode,
        ),
    )

    fun supportSummary(): String = CapabilityPresentation.supportSummary(_capabilities.value)

    // ------------------------------------------------------------------ capabilities

    fun refreshCapabilities() {
        viewModelScope.launch {
            _reDetecting.value = true
            _capabilities.value = runCatching { container.metricRepository.refreshCapabilities() }
                .getOrElse { _capabilities.value }
            _reDetecting.value = false
            _message.value = AdvancedMessage.CAPABILITIES_REFRESHED
        }
    }

    // ------------------------------------------------------------------ configuration

    fun resetToDefaults() {
        viewModelScope.launch {
            runCatching { container.configRepository.resetToDefaults() }
                .onSuccess {
                    AppLog.setVerbose(config.value.android.verboseLogging)
                    _message.value = AdvancedMessage.RESET_APPLIED
                }
                .onFailure { _message.value = AdvancedMessage.IMPORT_FAILED }
            refreshLog()
        }
    }

    /** Called by the screen after a successful/failed export or import so the user gets feedback. */
    fun reportExport(success: Boolean) {
        _message.value = if (success) AdvancedMessage.EXPORT_OK else AdvancedMessage.EXPORT_FAILED
    }

    fun reportImport(success: Boolean) {
        _message.value = if (success) AdvancedMessage.IMPORT_OK else AdvancedMessage.IMPORT_FAILED
    }

    private fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    private fun readLogLines(): List<String> = runCatching {
        AppLog.entries().map { it.format() }
    }.getOrDefault(emptyList())

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AdvancedViewModel(container) }
        }
    }
}
