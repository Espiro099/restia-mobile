package com.restia.mobile.ui.cash

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CashMovementDto
import com.restia.mobile.core.network.CashRegisterDto
import com.restia.mobile.core.network.CashShiftDto
import com.restia.mobile.core.network.CashShiftSummaryDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.ReconcileRequest
import com.restia.mobile.core.network.RefundDto
import com.restia.mobile.core.network.RefundResultRequest
import com.restia.mobile.data.cash.CASH_CURRENCY
import com.restia.mobile.data.cash.CashRepository
import com.restia.mobile.data.cash.RegisterShift
import com.restia.mobile.ui.checks.amountOf
import com.restia.mobile.ui.checks.parseAmount
import com.restia.mobile.ui.orders.moneyText
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Permisos de caja del empleado; ver la pantalla exige cash.read. Los de pagos y reembolsos, para resolver lo que traba el corte. */
data class CashPermissions(
    val open: Boolean,
    val move: Boolean,
    val close: Boolean,
    val readPayments: Boolean = false,
    val reconcilePayments: Boolean = false,
    val readRefunds: Boolean = false,
    val refund: Boolean = false,
    val refundResult: Boolean = false,
    val reconcileRefunds: Boolean = false,
)

private fun validReason(text: String) = text.trim().length >= 3

/** Qué se resuelve: conciliar un pago UNKNOWN, reembolsar un pago, o el resultado o la conciliación de un reembolso. */
enum class ResolveKind { RECONCILE_PAYMENT, REFUND, REFUND_RESULT, RECONCILE_REFUND }

/** Un pago confirmado sin reembolso se puede reembolsar completo, con su propina (P-7). */
fun isRefundable(payment: PaymentDto): Boolean = payment.status == "CONFIRMED" && payment.refundId == null

/**
 * Formulario único para resolver un pago o un reembolso. CONFIRMED exige la referencia de la terminal o
 * la transferencia; conciliar y reembolsar exigen motivo. Registrar el resultado de un reembolso no lo pide.
 */
data class ResolveForm(
    val kind: ResolveKind,
    val payment: PaymentDto? = null,
    val refund: RefundDto? = null,
    val outcome: String = "CONFIRMED",
    val reference: String = "",
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val asksOutcome: Boolean get() = kind != ResolveKind.REFUND
    val asksReason: Boolean get() = kind != ResolveKind.REFUND_RESULT
    val needsReference: Boolean get() = asksOutcome && outcome == "CONFIRMED"
    val canSubmit: Boolean
        get() = !submitting && (!needsReference || reference.isNotBlank()) && (!asksReason || validReason(reason))
}

/** Diferencia del corte: lo contado menos lo esperado. Positiva sobra; negativa falta. */
fun closingDifference(counted: BigDecimal, expected: BigDecimal): BigDecimal = counted.subtract(expected)

/**
 * Fondo inicial. El backend pide motivo solo si el fondo difiere de lo que dejó el turno anterior: la
 * primera vez se manda sin él, y si responde CASH_SHIFT_REASON_REQUIRED se muestra el campo con la diferencia.
 */
data class OpenForm(
    val register: CashRegisterDto,
    val amount: String = "",
    val reason: String = "",
    val difference: String? = null,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val needsReason: Boolean get() = difference != null
    val canSubmit: Boolean get() = !submitting && parseAmount(amount) != null && (!needsReason || validReason(reason))
}

/** DEPOSIT o WITHDRAWAL, siempre mayor que 0 y con motivo. */
data class MoveForm(
    val type: String,
    val amount: String = "",
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && (parseAmount(amount)?.signum() ?: 0) > 0 && validReason(reason)
}

/** Corte final: lo contado contra el esperado. Sin operaciones sin resolver (requisito para cerrar), el esperado ya no cambia. */
data class CloseForm(
    val expected: BigDecimal,
    val counted: String = "",
    val left: String = "",
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val countedValue: BigDecimal? get() = parseAmount(counted)
    val leftValue: BigDecimal? get() = parseAmount(left)
    val difference: BigDecimal? get() = countedValue?.let { closingDifference(it, expected) }
    val needsReason: Boolean get() = difference?.signum()?.let { it != 0 } ?: false
    val leftTooHigh: Boolean get() = countedValue != null && leftValue != null && leftValue!! > countedValue!!
    val canSubmit: Boolean
        get() = !submitting && countedValue != null && leftValue != null && !leftTooHigh && (!needsReason || validReason(reason))
}

/** Cancelar el cierre devuelve el turno a OPEN; el backend pide el motivo. */
data class CancelForm(val reason: String = "", val submitting: Boolean = false, val failure: ApiFailure? = null) {
    val canSubmit: Boolean get() = !submitting && validReason(reason)
}

data class ShiftDetail(
    val register: CashRegisterDto,
    val shift: CashShiftDto,
    val summary: CashShiftSummaryDto? = null,
    val movements: List<CashMovementDto> = emptyList(),
    val payments: List<PaymentDto> = emptyList(),
    /** Reembolsos PENDING o UNKNOWN registrados en este turno. */
    val refunds: List<RefundDto> = emptyList(),
    val loading: Boolean = false,
    val failure: ApiFailure? = null,
) {
    /** Pagos o reembolsos sin resolver: el backend no deja cerrar hasta conciliarlos. */
    val unresolved: Int get() = summary?.unresolved?.total ?: 0
}

data class Notice(@StringRes val text: Int, val arg: String? = null)

data class CashUiState(
    val registers: List<RegisterShift> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: ApiFailure? = null,
    val opening: OpenForm? = null,
    val detail: ShiftDetail? = null,
    val moving: MoveForm? = null,
    val confirmingStart: Boolean = false,
    val closing: CloseForm? = null,
    val cancelling: CancelForm? = null,
    val resolving: ResolveForm? = null,
    val working: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val notice: Notice? = null,
)

class CashViewModel(
    private val repository: CashRepository,
    private val branchId: String,
    val can: CashPermissions,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(CashUiState())
    val state: StateFlow<CashUiState> = _state

    // Mismo intento, misma clave: si se pierde la respuesta, el reintento no abre ni mueve dos veces.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = repository.overview(branchId)) {
                is ApiResult.Success -> _state.value.copy(registers = r.value, loaded = true, loading = false, failure = null)
                is ApiResult.Failure -> _state.value.copy(loading = false, failure = r.failure)
            }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    // --- Abrir turno ---

    fun startOpen(register: CashRegisterDto) {
        if (!can.open) return
        _state.value = _state.value.copy(opening = OpenForm(register), notice = null)
    }

    fun editOpen(transform: (OpenForm) -> OpenForm) {
        val form = _state.value.opening ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(opening = transform(form).copy(failure = null))
    }

    fun dismissOpen() {
        if (_state.value.opening?.submitting == true) return
        _state.value = _state.value.copy(opening = null)
    }

    fun submitOpen() {
        val form = _state.value.opening ?: return
        if (!form.canSubmit) return
        val amount = parseAmount(form.amount) ?: return
        val reason = form.reason.trim().takeIf { form.needsReason }
        val key = keyFor(Triple(form.register.id, amount, reason))
        _state.value = _state.value.copy(opening = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.open(branchId, key, form.register.id, amount, reason)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        opening = null,
                        registers = _state.value.registers.map { if (it.register.id == form.register.id) it.copy(shift = r.value) else it },
                        notice = Notice(R.string.cash_opened, form.register.name),
                    )
                    refresh()
                }
                is ApiResult.Failure -> {
                    val server = r.failure as? ApiFailure.Server
                    // Difiere de lo que dejó el turno anterior: se pide el motivo con la diferencia.
                    val difference = if (server?.code == "CASH_SHIFT_REASON_REQUIRED") {
                        server.details?.get("openingDifference")?.jsonPrimitive?.contentOrNull ?: ""
                    } else {
                        form.difference
                    }
                    val failure = if (server?.code == "CASH_SHIFT_REASON_REQUIRED" && !form.needsReason) null else r.failure
                    _state.value = _state.value.copy(opening = form.copy(submitting = false, difference = difference, failure = failure))
                    if (server?.code == "CASH_SHIFT_ALREADY_OPEN") refresh()
                }
            }
        }
    }

    // --- Detalle del turno ---

    fun view(row: RegisterShift) {
        val shift = row.shift ?: return
        _state.value = _state.value.copy(detail = ShiftDetail(row.register, shift, loading = true), actionFailure = null, notice = null)
        loadDetail()
    }

    fun closeDetail() {
        if (_state.value.working) return
        _state.value = _state.value.copy(
            detail = null, moving = null, closing = null, cancelling = null, resolving = null, confirmingStart = false, actionFailure = null,
        )
        refresh()
    }

    fun loadDetail() {
        val detail = _state.value.detail ?: return
        _state.value = _state.value.copy(detail = detail.copy(loading = true))
        viewModelScope.launch {
            val shift = repository.shift(branchId, detail.shift.id)
            val summary = repository.summary(branchId, detail.shift.id)
            val movements = repository.movements(branchId, detail.shift.id)
            val payments = if (can.readPayments) repository.payments(branchId, detail.shift.id) else ApiResult.Success(emptyList())
            val refunds = if (can.readRefunds) repository.unresolvedRefunds(branchId, detail.shift.id) else ApiResult.Success(emptyList())
            val current = _state.value.detail ?: return@launch
            val failure = listOf(shift, summary, movements, payments, refunds).filterIsInstance<ApiResult.Failure>().firstOrNull()?.failure
            _state.value = _state.value.copy(
                detail = current.copy(
                    shift = (shift as? ApiResult.Success)?.value ?: current.shift,
                    summary = (summary as? ApiResult.Success)?.value ?: current.summary,
                    movements = (movements as? ApiResult.Success)?.value ?: current.movements,
                    payments = (payments as? ApiResult.Success)?.value ?: current.payments,
                    refunds = (refunds as? ApiResult.Success)?.value ?: current.refunds,
                    loading = false,
                    failure = failure,
                ),
            )
        }
    }

    // --- Resolver pagos y reembolsos ---

    fun startReconcilePayment(payment: PaymentDto) {
        if (!can.reconcilePayments || payment.status != "UNKNOWN") return
        _state.value = _state.value.copy(resolving = ResolveForm(ResolveKind.RECONCILE_PAYMENT, payment = payment), notice = null)
    }

    /** El reembolso va al turno que se está viendo, que debe estar OPEN. */
    fun startRefund(payment: PaymentDto) {
        if (!can.refund || !isRefundable(payment) || _state.value.detail?.shift?.status != "OPEN") return
        _state.value = _state.value.copy(resolving = ResolveForm(ResolveKind.REFUND, payment = payment), notice = null)
    }

    fun startRefundResolution(refund: RefundDto) {
        val form = when {
            refund.status == "PENDING" && can.refundResult -> ResolveForm(ResolveKind.REFUND_RESULT, refund = refund)
            refund.status == "UNKNOWN" && can.reconcileRefunds -> ResolveForm(ResolveKind.RECONCILE_REFUND, refund = refund)
            else -> return
        }
        _state.value = _state.value.copy(resolving = form, notice = null)
    }

    fun editResolve(transform: (ResolveForm) -> ResolveForm) {
        val form = _state.value.resolving ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(resolving = transform(form).copy(failure = null))
    }

    fun dismissResolve() {
        if (_state.value.resolving?.submitting == true) return
        _state.value = _state.value.copy(resolving = null)
    }

    fun submitResolve() {
        val form = _state.value.resolving ?: return
        val detail = _state.value.detail ?: return
        if (!form.canSubmit) return
        val reference = form.reference.trim().takeIf { form.needsReference }
        val reason = form.reason.trim()
        val target = form.payment?.let { it.id to it.version } ?: form.refund?.let { it.id to it.version }
        val key = keyFor(listOf(form.kind, target, form.outcome, reference, reason))
        _state.value = _state.value.copy(resolving = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val r: ApiResult<Any> = when (form.kind) {
                ResolveKind.RECONCILE_PAYMENT -> repository.reconcilePayment(branchId, form.payment!!, key, ReconcileRequest(form.outcome, reference, reason))
                ResolveKind.REFUND -> repository.refund(branchId, form.payment!!, key, detail.shift.id, reason)
                ResolveKind.REFUND_RESULT -> repository.refundResult(branchId, form.refund!!, key, RefundResultRequest(form.outcome, reference))
                ResolveKind.RECONCILE_REFUND -> repository.reconcileRefund(branchId, form.refund!!, key, ReconcileRequest(form.outcome, reference, reason))
            }
            when (r) {
                is ApiResult.Success -> {
                    val notice = when (form.kind) {
                        ResolveKind.REFUND -> if ((r.value as RefundDto).status == "PENDING") R.string.cash_refund_pending else R.string.cash_refund_done
                        else -> if (form.outcome == "CONFIRMED") R.string.cash_resolved_confirmed else R.string.cash_resolved_failed
                    }
                    _state.value = _state.value.copy(resolving = null, notice = Notice(notice))
                    loadDetail()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(resolving = form.copy(submitting = false, failure = r.failure))
                    // Lo resolvió otro dispositivo o cambió de estado: se muestra lo vigente.
                    val code = (r.failure as? ApiFailure.Server)?.code
                    if (code in setOf("VERSION_CONFLICT", "PAYMENT_INVALID_STATE", "REFUND_INVALID_STATE", "REFUND_ALREADY_EXISTS", "PAYMENT_NOT_REFUNDABLE")) loadDetail()
                }
            }
        }
    }

    // --- Depósitos y retiros ---

    fun startMove(type: String) {
        val detail = _state.value.detail ?: return
        if (!can.move || detail.shift.status != "OPEN") return
        _state.value = _state.value.copy(moving = MoveForm(type), notice = null)
    }

    fun editMove(transform: (MoveForm) -> MoveForm) {
        val form = _state.value.moving ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(moving = transform(form).copy(failure = null))
    }

    fun dismissMove() {
        if (_state.value.moving?.submitting == true) return
        _state.value = _state.value.copy(moving = null)
    }

    fun submitMove() {
        val form = _state.value.moving ?: return
        val detail = _state.value.detail ?: return
        if (!form.canSubmit) return
        val amount = parseAmount(form.amount) ?: return
        val reason = form.reason.trim()
        val key = keyFor(listOf(detail.shift.id, form.type, amount, reason))
        _state.value = _state.value.copy(moving = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.move(branchId, detail.shift.id, key, form.type, amount, reason)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(
                        moving = null,
                        notice = Notice(if (form.type == "DEPOSIT") R.string.cash_deposit_done else R.string.cash_withdrawal_done),
                    )
                    loadDetail()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(moving = form.copy(submitting = false, failure = r.failure))
                    // Otro dispositivo inició el cierre: se recarga para mostrarlo.
                    if ((r.failure as? ApiFailure.Server)?.code == "CASH_SHIFT_NOT_OPEN") loadDetail()
                }
            }
        }
    }

    // --- Corte en dos pasos ---

    fun askStartClosing() {
        val detail = _state.value.detail ?: return
        if (!can.close || detail.shift.status != "OPEN") return
        _state.value = _state.value.copy(confirmingStart = true, actionFailure = null, notice = null)
    }

    fun dismissStartClosing() {
        if (_state.value.working) return
        _state.value = _state.value.copy(confirmingStart = false)
    }

    /** Bloquea cobros, depósitos y retiros nuevos; los ya iniciados pueden terminar o conciliarse. */
    fun confirmStartClosing() {
        val detail = _state.value.detail ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, actionFailure = null)
        viewModelScope.launch {
            val r = repository.startClosing(branchId, detail.shift)
            _state.value = _state.value.copy(
                working = false,
                confirmingStart = false,
                actionFailure = (r as? ApiResult.Failure)?.failure,
                detail = _state.value.detail?.let { d -> (r as? ApiResult.Success)?.value?.let { d.copy(shift = it) } ?: d },
            )
            loadDetail()
        }
    }

    fun startCancel() {
        val detail = _state.value.detail ?: return
        if (!can.close || detail.shift.status != "CLOSING") return
        _state.value = _state.value.copy(cancelling = CancelForm(), actionFailure = null, notice = null)
    }

    fun editCancel(text: String) {
        val form = _state.value.cancelling ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(cancelling = form.copy(reason = text.take(500), failure = null))
    }

    fun dismissCancel() {
        if (_state.value.cancelling?.submitting == true) return
        _state.value = _state.value.copy(cancelling = null)
    }

    fun submitCancel() {
        val form = _state.value.cancelling ?: return
        val detail = _state.value.detail ?: return
        if (!form.canSubmit) return
        _state.value = _state.value.copy(cancelling = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.cancelClosing(branchId, detail.shift, form.reason.trim())) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(cancelling = null, detail = _state.value.detail?.copy(shift = r.value))
                    loadDetail()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(cancelling = form.copy(submitting = false, failure = r.failure))
                    if ((r.failure as? ApiFailure.Server)?.code == "VERSION_CONFLICT") loadDetail()
                }
            }
        }
    }

    fun startClose() {
        val detail = _state.value.detail ?: return
        val summary = detail.summary ?: return
        if (!can.close || detail.shift.status != "CLOSING") return
        _state.value = _state.value.copy(closing = CloseForm(expected = amountOf(summary.expectedCash)), actionFailure = null, notice = null)
    }

    fun editClose(transform: (CloseForm) -> CloseForm) {
        val form = _state.value.closing ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(closing = transform(form).copy(failure = null))
    }

    fun dismissClose() {
        if (_state.value.closing?.submitting == true) return
        _state.value = _state.value.copy(closing = null)
    }

    fun submitClose() {
        val form = _state.value.closing ?: return
        val detail = _state.value.detail ?: return
        if (!form.canSubmit) return
        val counted = form.countedValue ?: return
        val left = form.leftValue ?: return
        val reason = form.reason.trim().takeIf { form.needsReason }
        val key = keyFor(listOf(detail.shift.id, detail.shift.version, counted, left, reason))
        _state.value = _state.value.copy(closing = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.close(branchId, detail.shift, key, counted, left, reason)) {
                is ApiResult.Success -> {
                    val difference = r.value.difference?.let { amountOf(it) } ?: form.difference ?: BigDecimal.ZERO
                    _state.value = _state.value.copy(
                        closing = null,
                        detail = null,
                        notice = if (difference.signum() == 0) Notice(R.string.cash_closed, detail.register.name)
                        // "Faltaron" o "sobraron" con el importe en positivo: "$-14.00" no se lee bien.
                        else Notice(if (difference.signum() < 0) R.string.cash_closed_short else R.string.cash_closed_over, moneyText(difference.abs(), CASH_CURRENCY)),
                    )
                    refresh()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(closing = form.copy(submitting = false, failure = r.failure))
                    val code = (r.failure as? ApiFailure.Server)?.code
                    // El esperado cambió (se resolvió algo) o lo cambió otro dispositivo: se muestra lo vigente.
                    if (code in setOf("VERSION_CONFLICT", "CASH_SHIFT_HAS_UNRESOLVED_OPERATIONS", "CASH_SHIFT_REASON_REQUIRED")) loadDetail()
                }
            }
        }
    }

    class Factory(
        private val repository: CashRepository,
        private val branchId: String,
        private val can: CashPermissions,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CashViewModel(repository, branchId, can) as T
    }
}
