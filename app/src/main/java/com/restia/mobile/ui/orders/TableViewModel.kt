package com.restia.mobile.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.core.network.UnitVersion
import com.restia.mobile.data.orders.OrderRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val MAX_UNITS_PER_BATCH = 50

/** Unidades listas para entregar, con la versión que exige el contrato (O-5). */
fun readyUnits(items: List<OrderItemDto>): List<UnitVersion> =
    items.flatMap { item -> item.units.filter { it.status == "READY" }.map { UnitVersion(it.id, it.version) } }

data class TableUiState(
    val orders: List<OrderDto> = emptyList(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    val delivering: Boolean = false,
    val deliverFailure: ApiFailure? = null,
    val sent: Boolean = false,
) {
    val readyCount: Int get() = orders.sumOf { order -> readyUnits(order.items).size }
}

class TableViewModel(
    private val repository: OrderRepository,
    private val branchId: String,
    private val sessionId: String,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(TableUiState())
    val state: StateFlow<TableUiState> = _state

    private var polling: Job? = null
    private var intent: List<UnitVersion>? = null
    private var intentKey = ""

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

    fun notifySent() {
        _state.value = _state.value.copy(sent = true)
    }

    fun clearSent() {
        _state.value = _state.value.copy(sent = false)
    }

    private suspend fun load() {
        _state.value = when (val r = repository.listForSession(branchId, sessionId)) {
            is ApiResult.Success -> _state.value.copy(orders = r.value, loaded = true, failure = null)
            // Conserva lo último que se vio: el aviso indica que puede estar desactualizado.
            is ApiResult.Failure -> _state.value.copy(failure = r.failure)
        }
    }

    fun deliverItem(item: OrderItemDto) = deliver(readyUnits(listOf(item)))

    fun deliverAll() = deliver(_state.value.orders.flatMap { readyUnits(it.items) })

    private fun deliver(units: List<UnitVersion>) {
        if (units.isEmpty() || _state.value.delivering) return
        val batch = units.take(MAX_UNITS_PER_BATCH)
        // Mismo lote, misma clave: reintentar tras perder la respuesta no repite la entrega.
        if (intent != batch) {
            intent = batch
            intentKey = newKey()
        }
        _state.value = _state.value.copy(delivering = true, deliverFailure = null)
        viewModelScope.launch {
            val result = repository.deliver(branchId, intentKey, batch)
            _state.value = _state.value.copy(
                delivering = false,
                deliverFailure = (result as? ApiResult.Failure)?.failure,
            )
            if (result is ApiResult.Success) intent = null
            // Tanto si entró como si otro empleado se adelantó, hay que ver el estado actual.
            load()
        }
    }

    class Factory(
        private val repository: OrderRepository,
        private val branchId: String,
        private val sessionId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TableViewModel(repository, branchId, sessionId) as T
    }

    private companion object {
        const val POLL_MILLIS = 15_000L
    }
}
