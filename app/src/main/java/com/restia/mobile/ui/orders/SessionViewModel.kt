package com.restia.mobile.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.SessionTableDto
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.network.TableSessionDto
import com.restia.mobile.data.tables.TablesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Una mesa que se puede juntar: activa, sin sesión y sin limpieza pendiente. */
fun canJoin(table: TableDto): Boolean =
    table.status == "ACTIVE" && table.currentSessionId == null && table.operationalState in setOf(null, "LIBRE", "POSIBLE_OCUPACION")

/** Elegir mesas libres para juntarlas a la sesión. `candidates` es null mientras se cargan. */
data class JoinState(
    val candidates: List<TableDto>? = null,
    val selected: Set<String> = emptySet(),
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canConfirm: Boolean get() = !submitting && selected.isNotEmpty()
}

/** Separar una mesa de la sesión; por omisión queda por limpiar. */
data class ReleaseState(
    val tableId: String? = null,
    val requiresCleaning: Boolean = true,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canConfirm: Boolean get() = !submitting && tableId != null
}

/** Anular una apertura por error: solo sin platillos ni pagos, con motivo. */
data class CancelSessionState(val reason: String = "", val submitting: Boolean = false, val failure: ApiFailure? = null) {
    val canConfirm: Boolean get() = !submitting && isValidReason(reason)
}

data class SessionUiState(
    /** Mesas sentadas de la sesión; vacío mientras no se ha leído. */
    val seated: List<SessionTableDto> = emptyList(),
    val joining: JoinState? = null,
    val releasing: ReleaseState? = null,
    val cancelling: CancelSessionState? = null,
    /** Números de las mesas que se acaban de juntar o la que se liberó, para el aviso. */
    val joined: List<Int>? = null,
    val released: Int? = null,
    /** La apertura se anuló: la pantalla vuelve a las mesas. */
    val cancelled: Boolean = false,
) {
    val busy: Boolean get() = joining?.submitting == true || releasing?.submitting == true || cancelling?.submitting == true
}

/**
 * Cambios a las mesas de una sesión (T-2): juntar, separar y anular. Cada cambio lee la sesión antes, para
 * mandar su versión en If-Match; si otro empleado la cambió entre tanto, el 409 se muestra en el diálogo.
 */
class SessionViewModel(
    private val tables: TablesRepository,
    private val branchId: String,
    private val sessionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    fun load() {
        viewModelScope.launch { refreshSeated() }
    }

    private suspend fun refreshSeated(): TableSessionDto? =
        when (val r = tables.session(branchId, sessionId)) {
            is ApiResult.Success -> r.value.also { show(it) }
            is ApiResult.Failure -> null
        }

    private fun show(session: TableSessionDto) {
        _state.value = _state.value.copy(seated = session.tables.filter { it.phase == "SEATED" }.sortedBy { it.number })
    }

    /** Lee la sesión y, con su versión, aplica el cambio. */
    private suspend fun withVersion(change: suspend (Int) -> ApiResult<TableSessionDto>): ApiResult<TableSessionDto> =
        when (val current = tables.session(branchId, sessionId)) {
            is ApiResult.Failure -> current
            is ApiResult.Success -> change(current.value.version)
        }

    fun startJoin() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(joining = JoinState(), joined = null, released = null)
        viewModelScope.launch {
            val r = tables.list(branchId)
            val current = _state.value.joining ?: return@launch
            _state.value = _state.value.copy(
                joining = when (r) {
                    is ApiResult.Success -> current.copy(candidates = r.value.filter(::canJoin))
                    is ApiResult.Failure -> current.copy(candidates = emptyList(), failure = r.failure)
                },
            )
        }
    }

    fun toggleJoin(tableId: String) {
        val current = _state.value.joining ?: return
        if (current.submitting) return
        val selected = if (tableId in current.selected) current.selected - tableId else current.selected + tableId
        _state.value = _state.value.copy(joining = current.copy(selected = selected, failure = null))
    }

    fun dismissJoin() {
        if (_state.value.joining?.submitting == true) return
        _state.value = _state.value.copy(joining = null)
    }

    fun confirmJoin() {
        val current = _state.value.joining ?: return
        if (!current.canConfirm) return
        _state.value = _state.value.copy(joining = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val ids = current.selected.toList()
            when (val r = withVersion { tables.addTables(branchId, sessionId, it, ids) }) {
                is ApiResult.Success -> {
                    show(r.value)
                    val numbers = current.candidates.orEmpty().filter { it.id in current.selected }.map { it.number }.sorted()
                    _state.value = _state.value.copy(joining = null, joined = numbers)
                }
                is ApiResult.Failure -> {
                    // Otra mesa pudo ocuparse mientras tanto: se vuelve a leer qué sigue libre.
                    val fresh = (tables.list(branchId) as? ApiResult.Success)?.value?.filter(::canJoin)
                    val candidates = fresh ?: current.candidates
                    val still = candidates.orEmpty().map { it.id }.toSet()
                    _state.value = _state.value.copy(
                        joining = current.copy(candidates = candidates, selected = current.selected intersect still, submitting = false, failure = r.failure),
                    )
                }
            }
        }
    }

    fun startRelease() {
        if (_state.value.busy || _state.value.seated.size < 2) return
        _state.value = _state.value.copy(releasing = ReleaseState(), joined = null, released = null)
    }

    fun selectRelease(tableId: String) {
        val current = _state.value.releasing ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(releasing = current.copy(tableId = tableId, failure = null))
    }

    fun setReleaseCleaning(requiresCleaning: Boolean) {
        val current = _state.value.releasing ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(releasing = current.copy(requiresCleaning = requiresCleaning))
    }

    fun dismissRelease() {
        if (_state.value.releasing?.submitting == true) return
        _state.value = _state.value.copy(releasing = null)
    }

    fun confirmRelease() {
        val current = _state.value.releasing ?: return
        val tableId = current.tableId ?: return
        if (!current.canConfirm) return
        val number = _state.value.seated.firstOrNull { it.tableId == tableId }?.number
        _state.value = _state.value.copy(releasing = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = withVersion { tables.releaseTable(branchId, sessionId, it, tableId, current.requiresCleaning) }) {
                is ApiResult.Success -> {
                    show(r.value)
                    _state.value = _state.value.copy(releasing = null, released = number)
                }
                is ApiResult.Failure -> {
                    refreshSeated()
                    _state.value = _state.value.copy(releasing = current.copy(submitting = false, failure = r.failure))
                }
            }
        }
    }

    fun startCancel() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(cancelling = CancelSessionState(), joined = null, released = null)
    }

    fun setCancelReason(reason: String) {
        val current = _state.value.cancelling ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(cancelling = current.copy(reason = reason.take(REASON_MAX), failure = null))
    }

    fun dismissCancel() {
        if (_state.value.cancelling?.submitting == true) return
        _state.value = _state.value.copy(cancelling = null)
    }

    fun confirmCancel() {
        val current = _state.value.cancelling ?: return
        if (!current.canConfirm) return
        _state.value = _state.value.copy(cancelling = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val reason = current.reason.trim()
            when (val r = withVersion { tables.cancelSession(branchId, sessionId, it, reason) }) {
                is ApiResult.Success -> _state.value = _state.value.copy(cancelling = null, cancelled = true)
                is ApiResult.Failure -> {
                    // Ya no estaba activa: otro empleado la cerró o la anuló, y la mesa ya no es de esta pantalla.
                    val gone = (r.failure as? ApiFailure.Server)?.code == "SESSION_NOT_ACTIVE"
                    _state.value = if (gone) {
                        _state.value.copy(cancelling = null, cancelled = true)
                    } else {
                        _state.value.copy(cancelling = current.copy(submitting = false, failure = r.failure))
                    }
                }
            }
        }
    }

    fun consumeCancelled() {
        _state.value = _state.value.copy(cancelled = false)
    }

    fun clearNotice() {
        _state.value = _state.value.copy(joined = null, released = null)
    }

    class Factory(private val tables: TablesRepository, private val branchId: String, private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(tables, branchId, sessionId) as T
    }
}
