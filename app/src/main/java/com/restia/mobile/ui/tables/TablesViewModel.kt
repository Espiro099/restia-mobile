package com.restia.mobile.ui.tables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.tables.TablesRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

const val MAX_GUESTS = 200

/** Solo una mesa activa y sin sesión se puede abrir (T-1). */
fun isOpenable(table: TableDto): Boolean =
    table.status == "ACTIVE" && (table.operationalState == "LIBRE" || table.operationalState == "POSIBLE_OCUPACION")

data class OpenTableState(
    val table: TableDto,
    val guests: Int = 2,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
    /** Hora de la reserva próxima: pide confirmar antes de abrir (R-5). */
    val reservationStartAt: String? = null,
)

data class TablesUiState(
    val tables: List<TableDto> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Hay un error, y lo que se muestra puede estar desactualizado (A-7). */
    val failure: ApiFailure? = null,
    val opening: OpenTableState? = null,
    val openedTableNumber: Int? = null,
)

class TablesViewModel(
    private val repository: TablesRepository,
    private val branchId: String,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(TablesUiState())
    val state: StateFlow<TablesUiState> = _state

    private var polling: Job? = null

    // Misma intención, misma clave: un reintento tras perder la respuesta no abre otra sesión.
    private var intent: Triple<String, Int, Boolean>? = null
    private var intentKey: String = ""

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
            is ApiResult.Success -> _state.value.copy(tables = result.value, loaded = true, refreshing = false, failure = null)
            // Conserva la última lista conocida: una falla de red no debe vaciar el piso.
            is ApiResult.Failure -> _state.value.copy(refreshing = false, failure = result.failure)
        }
    }

    fun selectTable(table: TableDto) {
        if (!isOpenable(table)) return
        _state.value = _state.value.copy(opening = OpenTableState(table), openedTableNumber = null)
    }

    fun setGuests(guests: Int) {
        val opening = _state.value.opening ?: return
        if (opening.submitting) return
        _state.value = _state.value.copy(
            opening = opening.copy(guests = guests.coerceIn(1, MAX_GUESTS), failure = null, reservationStartAt = null),
        )
    }

    fun dismissOpen() {
        if (_state.value.opening?.submitting != true) _state.value = _state.value.copy(opening = null)
    }

    fun confirmOpen() {
        val opening = _state.value.opening ?: return
        if (opening.submitting) return
        val acknowledge = opening.reservationStartAt != null
        val key = keyFor(opening.table.id, opening.guests, acknowledge)
        _state.value = _state.value.copy(opening = opening.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.openSession(branchId, opening.table.id, opening.guests, acknowledge, key)) {
                is ApiResult.Success -> {
                    intent = null
                    _state.value = _state.value.copy(opening = null, openedTableNumber = opening.table.number)
                    load()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(opening = opening.copy(failure = r.failure, reservationStartAt = reservationStart(r.failure)))
                    // La mesa cambió de estado: la lista de atrás ya no es la correcta.
                    if ((r.failure as? ApiFailure.Server)?.status == 409) load()
                }
            }
        }
    }

    private fun keyFor(tableId: String, guests: Int, acknowledge: Boolean): String {
        val next = Triple(tableId, guests, acknowledge)
        if (intent != next) {
            intent = next
            intentKey = newKey()
        }
        return intentKey
    }

    private fun reservationStart(failure: ApiFailure): String? {
        val server = failure as? ApiFailure.Server ?: return null
        if (server.code != TABLE_RESERVED_SOON) return null
        return server.details?.get("reservationStartAt")?.jsonPrimitive?.contentOrNull ?: ""
    }

    class Factory(private val repository: TablesRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TablesViewModel(repository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 15_000L
        const val TABLE_RESERVED_SOON = "TABLE_RESERVED_SOON"
    }
}
