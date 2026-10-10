package com.glacierglimmer.endfieldchargeplus.permission

import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Read native eligibility before publishing permissions, including on the first Home screen. */
internal class PermissionStatusMonitor(
    private val scope: CoroutineScope,
    private val refreshEligibility: suspend () -> Unit,
    private val readPermissions: () -> List<PermissionState>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _permissions = MutableStateFlow<List<PermissionState>>(emptyList())
    val permissions = _permissions.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        if (job?.isActive == true) return
        _loading.value = true
        job = scope.launch(dispatcher) {
            try {
                refreshEligibility()
                readCurrent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("PermissionStatus", "Could not refresh permission eligibility", error)
            } finally {
                _loading.value = false
            }
        }
    }

    fun readCurrent() { _permissions.value = readPermissions() }
}
