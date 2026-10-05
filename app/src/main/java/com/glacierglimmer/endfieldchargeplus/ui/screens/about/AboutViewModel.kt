package com.glacierglimmer.endfieldchargeplus.ui.screens.about

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.diagnostics.DiagnosticsReport
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import com.glacierglimmer.endfieldchargeplus.ui.state.CapabilityPresentation
import kotlinx.coroutines.flow.StateFlow

/** About page state: product facts plus the honest capability summary of this device. */
class AboutViewModel(private val container: EcpContainer) : ViewModel() {

    val capabilities: StateFlow<HardwareCapabilities> = container.metricRepository.capabilities

    /** "N of M metrics supported" for the honest capability disclaimer. */
    fun supportSummary(): String = CapabilityPresentation.supportSummary(capabilities.value)

    /** The canonical diagnostics report, used by the "share device info" action. */
    fun diagnosticsText(context: Context): String = DiagnosticsReport.build(
        context = context,
        config = container.configRepository.config.value,
        capabilities = capabilities.value,
        permissions = container.permissionManager.all(),
    )

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AboutViewModel(container) }
        }
    }
}
