package com.restia.mobile.ui.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.tables.TablesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TablesUiState(
    val tables: List<TableDto> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Hay un error, y lo que se muestra puede estar desactualizado (A-7). */
    val failure: ApiFailure? = null,
)

class TablesViewModel(
    private val repository: TablesRepository,
    private val branchId: String,
    private val pollMillis: Long = POLL_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow(TablesUiState())
    val state: StateFlow<TablesUiState> = _state

    private var polling: Job? = null

    /** Consulta periódica mientras la pantalla está visible (RT-1). */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                load()
                delay(pollMillis)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.value = _state.value.copy(refreshing = true)
        _state.value = when (val result = repository.list(branchId)) {
            is ApiResult.Success -> TablesUiState(tables = result.value, loaded = true)
            // Conserva la última lista conocida: una falla de red no debe vaciar el piso.
            is ApiResult.Failure -> _state.value.copy(refreshing = false, failure = result.failure)
        }
    }

    class Factory(private val repository: TablesRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TablesViewModel(repository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 15_000L
    }
}
