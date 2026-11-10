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
import com.restia.mobile.data.tables.TablesRepository
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

/** Unidades que se pueden anular: cocina ya las empezó o están listas (O-3). */
fun voidableUnits(item: OrderItemDto): List<UnitVersion> =
    item.units.filter { it.status == "PREPARING" || it.status == "READY" }.map { UnitVersion(it.id, it.version) }

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

/** Token de un gerente (D-37): sirve una vez, para estas unidades y este motivo. */
data class Approval(val token: String, val units: List<UnitVersion>, val reason: String, val validUntil: Long) {
    override fun toString(): String = "Approval(units=$units)"
}

data class VoidState(
    val item: OrderItemDto,
    val count: Int,
    /** Sin `order_units.void`, un gerente autoriza en este dispositivo. */
    val needsApproval: Boolean,
    val reason: String = "",
    val approverEmail: String = "",
    /** Solo en memoria y solo hasta pedir el token. */
    val approverPassword: String = "",
    val approval: Approval? = null,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val available: Int get() = voidableUnits(item).size
    val units: List<UnitVersion> get() = voidableUnits(item).take(count)

    /** Hay token para lo que está elegido ahora; si cambia la cantidad o el motivo, ya no sirve. */
    val approved: Boolean get() = approval != null && approval.units == units && approval.reason == reason.trim()
    val showsApprover: Boolean get() = needsApproval && !approved
    private val hasCredentials: Boolean get() = approverEmail.trim().contains('@') && approverPassword.isNotEmpty()
    val canConfirm: Boolean
        get() = !submitting && count in 1..available && isValidReason(reason) && (!showsApprover || hasCredentials)

    // La contraseña nunca debe aparecer en un log o un reporte de fallo.
    override fun toString(): String = "VoidState(item=${item.id}, count=$count, needsApproval=$needsApproval, approved=$approved)"
}

/** Confirmación de cierre (T-4): la mesa queda por limpiar. */
data class CloseState(val submitting: Boolean = false, val failure: ApiFailure? = null)

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
    val voiding: VoidState? = null,
    val voided: Int? = null,
    /** Acción de actionFailure: el mismo código se explica distinto al cancelar que al anular. */
    val failedAction: String? = null,
    val closing: CloseState? = null,
    /** La sesión se cerró: la pantalla vuelve a las mesas y lo consume. */
    val closed: Boolean = false,
) {
    val readyCount: Int get() = orders.sumOf { order -> readyUnits(order.items).size }
    val busy: Boolean get() = delivering || cancelling?.submitting == true || voiding?.submitting == true || closing?.submitting == true
}

class TableViewModel(
    private val repository: OrderRepository,
    private val branchId: String,
    private val sessionId: String,
    /** `order_units.void`: anula sin pedir autorización. */
    private val canVoidDirectly: Boolean = false,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sessions: TablesRepository? = null,
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

    fun clearVoided() {
        _state.value = _state.value.copy(voided = null)
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
        _state.value = _state.value.copy(delivering = true, actionFailure = null, cancelled = null, voided = null)
        viewModelScope.launch {
            val result = repository.deliver(branchId, key, batch)
            _state.value = _state.value.copy(
                delivering = false,
                actionFailure = (result as? ApiResult.Failure)?.failure,
                failedAction = "DELIVER",
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
        _state.value = _state.value.copy(cancelling = CancelState(item, available), actionFailure = null, cancelled = null, voided = null)
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
                        _state.value.copy(cancelling = null, actionFailure = failure, failedAction = "CANCEL")
                    }
                }
            }
            load()
        }
    }

    /** Por omisión propone anular todo lo empezado o listo de la partida. */
    fun startVoid(item: OrderItemDto) {
        val available = voidableUnits(item).size
        if (available == 0 || _state.value.busy) return
        _state.value = _state.value.copy(
            voiding = VoidState(item, available, needsApproval = !canVoidDirectly),
            actionFailure = null,
            cancelled = null,
            voided = null,
        )
    }

    private fun editVoid(change: (VoidState) -> VoidState) {
        val current = _state.value.voiding ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(voiding = change(current))
    }

    fun setVoidCount(count: Int) = editVoid { it.copy(count = count.coerceIn(1, it.available)) }

    fun setVoidReason(reason: String) = editVoid { it.copy(reason = reason.take(REASON_MAX)) }

    fun setApproverEmail(email: String) = editVoid { it.copy(approverEmail = email.take(254)) }

    fun setApproverPassword(password: String) = editVoid { it.copy(approverPassword = password.take(1024)) }

    fun dismissVoid() {
        if (_state.value.voiding?.submitting == true) return
        _state.value = _state.value.copy(voiding = null)
    }

    fun confirmVoid() {
        val current = _state.value.voiding ?: return
        if (!current.canConfirm || _state.value.delivering) return
        val units = current.units
        val reason = current.reason.trim()
        val key = keyFor(Triple("VOID", units, reason))
        _state.value = _state.value.copy(voiding = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            var approval = current.approval?.takeIf { current.approved && now() < it.validUntil }
            if (current.needsApproval && approval == null) {
                if (current.approved) {
                    // El token venció sin usarse: el gerente tiene que volver a escribir sus datos.
                    _state.value = _state.value.copy(voiding = current.copy(approval = null, failure = EXPIRED))
                    return@launch
                }
                val r = repository.approveVoid(branchId, current.approverEmail.trim(), current.approverPassword, units.map { it.id }, reason)
                when (r) {
                    is ApiResult.Success -> {
                        val margin = minOf(5, r.value.expiresIn / 2) * 1000L
                        approval = Approval(r.value.approvalToken, units, reason, now() + r.value.expiresIn * 1000L - margin)
                        // Ya no se necesita: no se queda en memoria más de lo necesario.
                        _state.value = _state.value.copy(voiding = current.copy(submitting = true, approval = approval, approverPassword = ""))
                    }
                    is ApiResult.Failure -> {
                        // Con la red caída se conserva para reintentar; si el servidor la rechazó, se borra.
                        val keep = if (isRetryable(r.failure)) current.approverPassword else ""
                        _state.value = _state.value.copy(voiding = current.copy(approverPassword = keep, failure = r.failure))
                        return@launch
                    }
                }
            }
            val sent = _state.value.voiding ?: current
            when (val result = repository.void(branchId, key, units, reason, approval?.token)) {
                is ApiResult.Success -> {
                    intent = null
                    _state.value = _state.value.copy(voiding = null, voided = units.size)
                }
                is ApiResult.Failure -> {
                    val failure = result.failure
                    _state.value = when {
                        // La respuesta pudo perderse: se reintenta con la misma clave y el mismo token.
                        isRetryable(failure) -> _state.value.copy(voiding = sent.copy(submitting = false, failure = failure))
                        // Venció, ya se usó o no corresponde: se pide otra autorización sin cerrar el diálogo.
                        failure is ApiFailure.Server && failure.code == "APPROVAL_INVALID" ->
                            _state.value.copy(voiding = sent.copy(submitting = false, approval = null, failure = failure))
                        else -> {
                            intent = null
                            _state.value.copy(voiding = null, actionFailure = failure, failedAction = "VOID")
                        }
                    }
                }
            }
            load()
        }
    }

    fun startClose() {
        if (sessions == null || _state.value.busy) return
        _state.value = _state.value.copy(closing = CloseState(), actionFailure = null)
    }

    fun dismissClose() {
        if (_state.value.closing?.submitting == true) return
        _state.value = _state.value.copy(closing = null)
    }

    fun confirmClose() {
        val repo = sessions ?: return
        val current = _state.value.closing ?: return
        if (current.submitting || _state.value.delivering) return
        _state.value = _state.value.copy(closing = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repo.closeSession(branchId, sessionId)) {
                is ApiResult.Success -> _state.value = _state.value.copy(closing = null, closed = true)
                is ApiResult.Failure -> {
                    // Ya cerrada por otro empleado: el resultado es el mismo que se buscaba.
                    val alreadyClosed = (r.failure as? ApiFailure.Server)?.code == "SESSION_NOT_ACTIVE"
                    _state.value = if (alreadyClosed) {
                        _state.value.copy(closing = null, closed = true)
                    } else {
                        _state.value.copy(closing = current.copy(submitting = false, failure = r.failure))
                    }
                    if (!alreadyClosed) load()
                }
            }
        }
    }

    fun consumeClosed() {
        _state.value = _state.value.copy(closed = false)
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

    class Factory(
        private val repository: OrderRepository,
        private val branchId: String,
        private val sessionId: String,
        private val canVoidDirectly: Boolean,
        private val sessions: TablesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TableViewModel(repository, branchId, sessionId, canVoidDirectly, sessions = sessions) as T
    }

    private companion object {
        const val POLL_MILLIS = 15_000L

        /** Se muestra igual que el APPROVAL_INVALID del servidor. */
        val EXPIRED = ApiFailure.Server(409, "APPROVAL_INVALID", null, null)
    }
}
