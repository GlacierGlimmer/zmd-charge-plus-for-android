package com.glacierglimmer.endfieldchargeplus.ui.screens.variables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableProjection
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Variable library state: the search/filter selection and the live availability of the projected
 * registry rows.
 */
class VariablesViewModel(private val container: EcpContainer) : ViewModel() {

    val snapshot: StateFlow<MetricSnapshot> = container.metricRepository.snapshot

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow(VariableSearch.ALL_CATEGORIES)
    val category: StateFlow<String> = _category.asStateFlow()

    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setCategory(value: String) {
        _category.value = value
    }

    fun select(name: String?) {
        _selectedName.value = name
    }

    /** The projected registry, with availability taken from [snapshot]. */
    fun rows(snapshot: MetricSnapshot, language: UiLanguage): List<VariableRow> =
        VariableProjection.rows(snapshot, language)

    /** Category keys in registry order. */
    fun categories(rows: List<VariableRow>): List<String> = VariableSearch.categories(rows)

    companion object {
        fun factory(container: EcpContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { VariablesViewModel(container) }
        }
    }
}
