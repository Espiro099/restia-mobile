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

/** Límites del motivo en OrderUnitTransitionRequest. */
const val REASON_MIN = 3
const val REASON_MAX = 500

/** Unidades listas para entregar, con la versión que exige el contrato (O-5). */
fun readyUnits(items: List<OrderItemDto>): List<UnitVersion> =
    items.flatMap { item -> item.units.filter { it.status == "READY" }.map { UnitVersion(it.id, it.version) } }

/** Unidades que todavía se pueden cancelar: cocina no las ha empezado. */
fun pendingUnits(item: OrderItemDto): List<UnitVersion> =
    item.units.filter { it.status == "PENDING" }.map { UnitVersion(it.id, it.version) }

fun isValidReason(reason: String): Boolean = reason.trim().length in REASON_MIN..REASON_MAX

data class CancelState(
    val item: OrderItemDto,
    val count: Int,
    val reason: String = "",
    val submitting: Boolean = false,
    /** Falla que permite reintentar desde el diálogo (red o servidor), con la misma clave. */
    val failure: ApiFailure? = null,
) {
    val available: Int get() = pendingUnits(item).size
    val canConfirm: Boolean get() = !submitting && count in 1..available && isValidReason(reason)
}

data class TableUiState(
    val orders: List<OrderDto> = emptyList(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    val delivering: Boolean = false,
    /** Resultado de la última entrega o cancelación que no se pudo aplicar. */
    val actionFailure: ApiFailure? = null,
    val sent: Boolean = false,
    val cancelling: CancelState? = null,
    /** Cuántas unidades se acaban de cancelar, para el aviso. */
    val cancelled: Int? = null,
) {
    val readyCount: Int get() = orders.sumOf { order -> readyUnits(order.items).size }
    val busy: Boolean get() = delivering || cancelling?.submitting == true
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

    // Mismo intento, misma clave: reintentar tras perder la respuesta no repite la operación.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

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

    fun clearCancelled() {
        _state.value = _state.value.copy(cancelled = null)
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
        if (units.isEmpty() || _state.value.busy) return
        val batch = units.take(MAX_UNITS_PER_BATCH)
        val key = keyFor(Pair("DELIVER", batch))
        _state.value = _state.value.copy(delivering = true, actionFailure = null, cancelled = null)
        viewModelScope.launch {
            val result = repository.deliver(branchId, key, batch)
            _state.value = _state.value.copy(
                delivering = false,
                actionFailure = (result as? ApiResult.Failure)?.failure,
            )
            if (result is ApiResult.Success) intent = null
            // Tanto si entró como si otro empleado se adelantó, hay que ver el estado actual.
            load()
        }
    }

    /** Por omisión propone cancelar todo lo pendiente de la partida; el mesero puede bajar la cantidad. */
    fun startCancel(item: OrderItemDto) {
        val available = pendingUnits(item).size
        if (available == 0 || _state.value.busy) return
        _state.value = _state.value.copy(cancelling = CancelState(item, available), actionFailure = null, cancelled = null)
    }

    fun setCancelCount(count: Int) {
        val current = _state.value.cancelling ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(cancelling = current.copy(count = count.coerceIn(1, current.available)))
    }

    fun setCancelReason(reason: String) {
        val current = _state.value.cancelling ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(cancelling = current.copy(reason = reason.take(REASON_MAX)))
    }

    fun dismissCancel() {
        if (_state.value.cancelling?.submitting == true) return
        _state.value = _state.value.copy(cancelling = null)
    }

    fun confirmCancel() {
        val current = _state.value.cancelling ?: return
        if (!current.canConfirm || _state.value.delivering) return
        val units = pendingUnits(current.item).take(current.count)
        val reason = current.reason.trim()
        val key = keyFor(Triple("CANCEL", units, reason))
        _state.value = _state.value.copy(cancelling = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val result = repository.cancel(branchId, key, units, reason)) {
                is ApiResult.Success -> {
                    intent = null
                    _state.value = _state.value.copy(cancelling = null, cancelled = units.size)
                }
                is ApiResult.Failure -> {
                    val failure = result.failure
                    _state.value = if (isRetryable(failure)) {
                        // La respuesta pudo perderse: el diálogo sigue abierto y reintentar usa la misma clave.
                        _state.value.copy(cancelling = current.copy(submitting = false, failure = failure))
                    } else {
                        // Cocina empezó, otro empleado cambió algo o ya se pagó: hay que ver el estado actual.
                        intent = null
                        _state.value.copy(cancelling = null, actionFailure = failure)
                    }
                }
            }
            load()
        }
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

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
