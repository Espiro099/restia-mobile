package com.restia.mobile.ui.kitchen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.core.network.QueueUnitDto
import com.restia.mobile.core.network.StationDto
import com.restia.mobile.core.network.UnitVersion
import com.restia.mobile.data.kitchen.KitchenRepository
import com.restia.mobile.ui.orders.MAX_UNITS_PER_BATCH
import com.restia.mobile.ui.orders.REASON_MAX
import com.restia.mobile.ui.orders.isValidReason
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Una partida de la comanda: el mismo platillo con los mismos modificadores y notas. */
data class KitchenLine(
    val orderItemId: String,
    val productName: String,
    val modifiers: List<String>,
    val notes: String?,
    val units: List<QueueUnitDto>,
) {
    val pending: List<UnitVersion> get() = units.filter { it.status == "PENDING" }.map { UnitVersion(it.id, it.version) }
    val preparing: List<UnitVersion> get() = units.filter { it.status == "PREPARING" }.map { UnitVersion(it.id, it.version) }
    val ready: Int get() = units.count { it.status == "READY" }
}

/** Una comanda en la cola: sus mesas, la hora de su primera unidad y sus partidas. */
data class KitchenTicket(val orderId: String, val tableNumbers: List<Int>, val createdAt: String, val lines: List<KitchenLine>) {
    val pending: List<UnitVersion> get() = lines.flatMap { it.pending }
    val preparing: List<UnitVersion> get() = lines.flatMap { it.preparing }
}

/** Agrupa por comanda y partida, conservando el orden de la cola (la más antigua primero). */
fun tickets(units: List<KitchenUnitDto>): List<KitchenTicket> =
    units.groupBy { it.unit.orderId }.map { (orderId, rows) ->
        val lines = rows.groupBy { it.unit.orderItemId }.map { (itemId, same) ->
            val first = same.first()
            KitchenLine(itemId, first.productName, first.modifiers.map { it.name }, first.notes, same.map { it.unit })
        }
        KitchenTicket(orderId, rows.first().tableNumbers, rows.minOf { it.unit.createdAt }, lines)
    }

/** Mover a otra estación lo pendiente de una partida, con motivo. `moved` cuenta lo que ya se movió. */
data class ReassignState(
    val line: KitchenLine,
    val stationId: String? = null,
    val reason: String = "",
    val submitting: Boolean = false,
    val moved: Int = 0,
    val failure: ApiFailure? = null,
) {
    val canConfirm: Boolean get() = !submitting && stationId != null && isValidReason(reason) && line.pending.isNotEmpty()
}

data class KitchenUiState(
    val stations: List<StationDto> = emptyList(),
    /** null: todas las estaciones. */
    val stationId: String? = null,
    val units: List<KitchenUnitDto> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val failure: ApiFailure? = null,
    val working: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val reassigning: ReassignState? = null,
    /** Cuántas unidades se acaban de mover y a qué estación, para el aviso. */
    val reassigned: Pair<Int, String>? = null,
) {
    val tickets: List<KitchenTicket> get() = tickets(units)
}

class KitchenViewModel(
    private val repository: KitchenRepository,
    private val branchId: String,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(KitchenUiState())
    val state: StateFlow<KitchenUiState> = _state

    private var polling: Job? = null

    // Mismo intento, misma clave: reintentar tras perder la respuesta no repite la transición.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

    /** Consulta frecuente mientras la pantalla está visible (RT-1). */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            if (_state.value.stations.isEmpty()) loadStations()
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
        viewModelScope.launch {
            if (_state.value.stations.isEmpty()) loadStations()
            load()
        }
    }

    fun selectStation(stationId: String?) {
        if (_state.value.stationId == stationId) return
        // La cola de otra estación no se mezcla con la anterior mientras carga.
        _state.value = _state.value.copy(stationId = stationId, units = emptyList(), loaded = false, actionFailure = null)
        refresh()
    }

    private suspend fun loadStations() {
        // Sin estaciones, la cola se ve completa: no impide trabajar.
        (repository.stations(branchId) as? ApiResult.Success)?.let { _state.value = _state.value.copy(stations = it.value) }
    }

    private suspend fun load() {
        val station = _state.value.stationId
        _state.value = _state.value.copy(refreshing = true)
        val r = repository.queue(branchId, station)
        // Si cambió la estación mientras cargaba, esta respuesta ya no aplica.
        if (_state.value.stationId != station) return
        _state.value = when (r) {
            is ApiResult.Success -> _state.value.copy(units = r.value, loaded = true, refreshing = false, failure = null)
            is ApiResult.Failure -> _state.value.copy(refreshing = false, failure = r.failure)
        }
    }

    fun start(line: KitchenLine) = transition("START", line.pending)

    fun ready(line: KitchenLine) = transition("READY", line.preparing)

    fun startAll(ticket: KitchenTicket) = transition("START", ticket.pending)

    fun readyAll(ticket: KitchenTicket) = transition("READY", ticket.preparing)

    private fun transition(action: String, units: List<UnitVersion>) {
        if (units.isEmpty() || _state.value.working) return
        val batch = units.take(MAX_UNITS_PER_BATCH)
        val key = keyFor(action to batch)
        _state.value = _state.value.copy(working = true, actionFailure = null)
        viewModelScope.launch {
            val r = if (action == "START") repository.start(branchId, key, batch) else repository.ready(branchId, key, batch)
            val failure = (r as? ApiResult.Failure)?.failure
            // Si el servidor la rechazó, otro intento es una operación nueva; sin respuesta, se repite con la misma clave.
            if (failure == null || !isRetryable(failure)) intent = null
            _state.value = _state.value.copy(working = false, actionFailure = failure)
            load()
        }
    }

    fun startReassign(line: KitchenLine) {
        if (_state.value.working || line.pending.isEmpty()) return
        _state.value = _state.value.copy(reassigning = ReassignState(line), reassigned = null, actionFailure = null)
    }

    fun selectReassignStation(stationId: String) {
        val current = _state.value.reassigning ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(reassigning = current.copy(stationId = stationId, failure = null))
    }

    fun setReassignReason(reason: String) {
        val current = _state.value.reassigning ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(reassigning = current.copy(reason = reason.take(REASON_MAX), failure = null))
    }

    fun dismissReassign() {
        if (_state.value.reassigning?.submitting == true) return
        _state.value = _state.value.copy(reassigning = null)
    }

    /**
     * Una petición por unidad, en orden. Si una falla, se detiene: lo ya movido queda movido, y el diálogo
     * sigue abierto con la partida actualizada para reintentar el resto.
     */
    fun confirmReassign() {
        val current = _state.value.reassigning ?: return
        val stationId = current.stationId ?: return
        if (!current.canConfirm) return
        _state.value = _state.value.copy(reassigning = current.copy(submitting = true, failure = null), working = true)
        viewModelScope.launch {
            val reason = current.reason.trim()
            var moved = current.moved
            var failure: ApiFailure? = null
            for (unit in current.line.pending) {
                when (val r = repository.reassign(branchId, unit, stationId, reason)) {
                    is ApiResult.Success -> moved++
                    is ApiResult.Failure -> {
                        failure = r.failure
                        break
                    }
                }
            }
            load()
            val name = _state.value.stations.firstOrNull { it.id == stationId }?.name.orEmpty()
            _state.value = if (failure == null) {
                _state.value.copy(reassigning = null, reassigned = moved to name, working = false)
            } else {
                // La partida con lo que siga pendiente, ya con sus versiones nuevas.
                val units = _state.value.units.filter { it.unit.orderItemId == current.line.orderItemId }.map { it.unit }
                _state.value.copy(
                    reassigning = current.copy(line = current.line.copy(units = units), submitting = false, moved = moved, failure = failure),
                    working = false,
                )
            }
        }
    }

    fun clearReassigned() {
        _state.value = _state.value.copy(reassigned = null)
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

    class Factory(private val repository: KitchenRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = KitchenViewModel(repository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 8_000L
    }
}
