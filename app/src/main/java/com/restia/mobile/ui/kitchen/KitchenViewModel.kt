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
