package com.restia.mobile.ui.checks

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CardDto
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PaymentCreateRequest
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.data.checks.CheckRepository
import com.restia.mobile.data.checks.ShiftOption
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val AMOUNT = Regex("""^\d{1,12}([.,]\d{1,2})?$""")

/** Importe escrito por el empleado: hasta 2 decimales, con punto o coma. null si no es válido. */
fun parseAmount(text: String): BigDecimal? {
    val clean = text.trim()
    if (!AMOUNT.matches(clean)) return null
    return BigDecimal(clean.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY)
}

fun amountOf(money: MoneyDto): BigDecimal = runCatching { BigDecimal(money.amount) }.getOrDefault(BigDecimal.ZERO)

/** Solo estas cuentas aceptan pagos (Decisiones_Payments). */
fun isPayable(check: CheckDto): Boolean =
    check.status in setOf("OPEN", "REQUESTED", "PARTIALLY_PAID") && amountOf(check.balance).signum() > 0

/** Lo que falta cubrir sin contar pagos en curso: el backend no deja pasar de ahí (P-3). */
fun available(check: CheckDto): BigDecimal = (amountOf(check.balance) - amountOf(check.pendingTotal)).max(BigDecimal.ZERO)

const val CASH = "CASH"
val METHODS = listOf(CASH, "CARD_CREDIT", "CARD_DEBIT", "TRANSFER")
val BRANDS = listOf("VISA", "MASTERCARD", "AMEX", "OTHER")
val TIP_OPTIONS = listOf(0, 5, 10, 20)

data class PayState(
    val check: CheckDto,
    val shiftId: String?,
    val method: String = CASH,
    val amount: String,
    val tip: String = "",
    val tendered: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
    val tipPercent: Int? = null,
) {
    val amountValue: BigDecimal? get() = parseAmount(amount)?.takeIf { it.signum() > 0 }
    val tipValue: BigDecimal?
        get() {
            val percent = tipPercent
            if (percent == null) return parseAmount(tip.ifBlank { "0" })
            if (percent == 0) return BigDecimal.ZERO.setScale(2)
            return amountValue?.multiply(BigDecimal.valueOf(percent.toLong()))?.movePointLeft(2)
                ?.setScale(2, RoundingMode.HALF_UP)
        }
    val totalValue: BigDecimal? get() = amountValue?.let { amount -> tipValue?.let(amount::add) }
    val tenderedValue: BigDecimal? get() = parseAmount(tendered)
    val exceeds: Boolean get() = amountValue?.let { it > available(check) } ?: false

    /** Cambio en efectivo; null si todavía no se puede calcular o no alcanza. */
    val change: BigDecimal?
        get() {
            val due = totalValue ?: return null
            return tenderedValue?.minus(due)?.takeIf { it.signum() >= 0 }
        }

    val canConfirm: Boolean
        get() = !submitting && shiftId != null && amountValue != null && !exceeds && tipValue != null &&
            (method != CASH || change != null)
}

data class ResultState(
    val payment: PaymentDto,
    val brand: String? = null,
    val reference: String = "",
    val last4: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val isCard: Boolean get() = payment.method.startsWith("CARD_")
    private val last4Valid: Boolean get() = last4.isEmpty() || Regex("""^\d{4}$""").matches(last4)

    /** Confirmar exige la referencia de la terminal o de la transferencia. */
    val canConfirm: Boolean get() = !submitting && reference.trim().length in 1..120 && last4Valid
    val canReject: Boolean get() = !submitting
}

/** Aviso de una acción que sí ocurrió. */
data class Notice(@StringRes val text: Int, val arg: String? = null)

data class CheckUiState(
    val checks: List<CheckDto> = emptyList(),
    val payments: Map<String, List<PaymentDto>> = emptyMap(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    val shifts: List<ShiftOption>? = null,
    val shiftsFailure: ApiFailure? = null,
    val shiftsLoading: Boolean = false,
    val paying: PayState? = null,
    val recording: ResultState? = null,
    val moving: MoveUnitsState? = null,
    val action: CheckAction? = null,
    val working: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val notice: Notice? = null,
    val receiptPreview: PaymentDto? = null,
) {
    val busy: Boolean
        get() = working || paying?.submitting == true || recording?.submitting == true || moving?.submitting == true || action?.submitting == true
}

class CheckViewModel(
    private val repository: CheckRepository,
    private val branchId: String,
    private val sessionId: String,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    /** checks.reopen y checks.adjust: sin ellos, un gerente autoriza en el dispositivo (C-9). */
    private val canReopenDirectly: Boolean = false,
    private val canAdjustDirectly: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(CheckUiState())
    val state: StateFlow<CheckUiState> = _state

    private var polling: Job? = null
    private var watchingReceipt: Job? = null

    // Mismo intento, misma clave: un reintento tras perder la respuesta no cobra dos veces.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

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
        watchingReceipt?.cancel()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private suspend fun load() {
        when (val r = repository.checks(branchId, sessionId)) {
            is ApiResult.Failure -> _state.value = _state.value.copy(failure = r.failure)
            is ApiResult.Success -> {
                val payments = mutableMapOf<String, List<PaymentDto>>()
                for (check in r.value.filter { it.status != "CANCELLED" }) {
                    when (val p = repository.payments(branchId, check.id)) {
                        is ApiResult.Success -> payments[check.id] = p.value
                        // Sin los pagos, lo demás sigue sirviendo; se conserva lo último que se vio.
                        is ApiResult.Failure -> _state.value.payments[check.id]?.let { payments[check.id] = it }
                    }
                }
                _state.value = _state.value.copy(checks = r.value, payments = payments, loaded = true, failure = null)
            }
        }
    }

    // --- Pedir la cuenta ---

    fun requestCheck(check: CheckDto) {
        if (check.status != "OPEN" || _state.value.busy) return
        _state.value = _state.value.copy(working = true, actionFailure = null, notice = null)
        viewModelScope.launch {
            val r = repository.request(branchId, check)
            _state.value = _state.value.copy(
                working = false,
                actionFailure = (r as? ApiResult.Failure)?.failure,
                notice = if (r is ApiResult.Success) Notice(R.string.check_requested_done) else null,
            )
            load()
        }
    }

    // --- Imprimir ---

    fun previewReceipt(payment: PaymentDto) {
        watchingReceipt?.cancel()
        if (payment.status == "CONFIRMED") _state.value = _state.value.copy(receiptPreview = payment)
    }

    fun consumeReceiptPreview() {
        _state.value = _state.value.copy(receiptPreview = null)
    }

    fun print(type: String, sourceId: String) {
        if (_state.value.busy) return
        val receipt = if (type == PAYMENT_RECEIPT) {
            _state.value.payments.values.asSequence().flatten().firstOrNull { it.id == sourceId && it.status == "CONFIRMED" }
        } else null
        if (receipt != null) watchingReceipt?.cancel()
        val key = keyFor(Pair(type, sourceId))
        _state.value = _state.value.copy(working = true, actionFailure = null, notice = null)
        viewModelScope.launch {
            val r = repository.print(branchId, key, type, sourceId)
            val failure = (r as? ApiResult.Failure)?.failure
            val fallback = receipt?.takeIf {
                failure == ApiFailure.Network || (failure as? ApiFailure.Server)?.code == "PRINT_ROUTE_NOT_CONFIGURED"
            }
            // Impreso o rechazado, otro toque es otra impresión; sin respuesta, el reintento no la duplica.
            if (failure == null || !isRetryable(failure)) intent = null
            _state.value = _state.value.copy(
                working = false,
                actionFailure = failure.takeIf { fallback == null },
                receiptPreview = fallback,
                notice = if (failure == null) Notice(if (type == PRE_CHECK) R.string.print_pre_check_sent else R.string.print_receipt_sent) else null,
            )
            if (receipt != null && r is ApiResult.Success && polling?.isActive == true) {
                watchReceipt(r.value.id, r.value.status, receipt)
            }
        }
    }

    private fun watchReceipt(jobId: String, initialStatus: String, receipt: PaymentDto) {
        watchingReceipt = viewModelScope.launch {
            var status = initialStatus
            for (attempt in 1..4) {
                if (status !in setOf("PENDING", "CLAIMED", "PRINTING")) break
                delay(3_000)
                when (val response = repository.printJob(branchId, jobId)) {
                    is ApiResult.Success -> status = response.value.status
                    is ApiResult.Failure -> if (response.failure is ApiFailure.Server && response.failure.status == 403) {
                        return@launch
                    }
                }
            }
            when (status) {
                "PRINTED" -> _state.value = _state.value.copy(notice = Notice(R.string.print_receipt_done))
                "PENDING", "CLAIMED", "PRINTING", "FAILED", "UNKNOWN", "CANCELLED" -> {
                    _state.value = _state.value.copy(
                        notice = Notice(R.string.print_receipt_pdf_fallback),
                        receiptPreview = receipt.takeUnless { _state.value.busy || _state.value.paying != null || _state.value.recording != null },
                    )
                }
            }
        }
    }

    // --- Cobrar ---

    fun startPay(check: CheckDto) {
        if (!isPayable(check) || _state.value.busy) return
        val amount = available(check).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val shifts = _state.value.shifts
        _state.value = _state.value.copy(
            paying = PayState(check, shiftId = shifts?.singleOrNull()?.id, amount = amount, tipPercent = 0),
            actionFailure = null,
            notice = null,
        )
        // Sin caja o tras un error se vuelve a preguntar: un gerente pudo abrir el turno desde el panel.
        if (shifts.isNullOrEmpty() || _state.value.shiftsFailure != null) loadShifts()
    }

    private fun loadShifts() {
        if (_state.value.shiftsLoading) return
        _state.value = _state.value.copy(shiftsLoading = true)
        viewModelScope.launch {
            when (val r = repository.openShifts(branchId)) {
                is ApiResult.Success -> {
                    val paying = _state.value.paying
                    // Una caja elegida que ya cerró no sirve; con una sola abierta no hay nada que elegir.
                    val kept = paying?.shiftId?.takeIf { id -> r.value.any { it.id == id } }
                    _state.value = _state.value.copy(
                        shifts = r.value,
                        shiftsFailure = null,
                        shiftsLoading = false,
                        paying = paying?.copy(shiftId = kept ?: r.value.singleOrNull()?.id),
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(shiftsFailure = r.failure, shiftsLoading = false)
            }
        }
    }

    fun retryShifts() = loadShifts()

    private fun editPay(change: (PayState) -> PayState) {
        val current = _state.value.paying ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(paying = change(current).copy(failure = null))
    }

    fun setMethod(method: String) = editPay { it.copy(method = method, tendered = if (method == CASH) it.tendered else "") }

    fun setAmount(text: String) = editPay { it.copy(amount = text.take(16)) }

    fun setTip(text: String) = editPay { it.copy(tip = text.take(16), tipPercent = null) }

    fun setTipPercent(percent: Int?) = editPay {
        if (percent == null || percent in TIP_OPTIONS) it.copy(tipPercent = percent) else it
    }

    fun setTendered(text: String) = editPay { it.copy(tendered = text.take(16)) }

    fun useExactCash() = editPay { it.copy(tendered = it.totalValue?.toPlainString().orEmpty()) }

    fun setShift(id: String) = editPay { it.copy(shiftId = id) }

    fun dismissPay() {
        if (_state.value.paying?.submitting == true) return
        _state.value = _state.value.copy(paying = null)
    }

    fun confirmPay() {
        val current = _state.value.paying ?: return
        if (!current.canConfirm) return
        val currency = current.check.balance.currency
        val money = { v: BigDecimal -> MoneyDto(v.toPlainString(), currency) }
        val tip = current.tipValue!!
        val body = PaymentCreateRequest(
            checkId = current.check.id,
            cashShiftId = current.shiftId!!,
            method = current.method,
            amount = money(current.amountValue!!),
            tip = tip.takeIf { it.signum() > 0 }?.let(money),
            amountTendered = if (current.method == CASH) money(current.tenderedValue!!) else null,
        )
        val key = keyFor(body)
        _state.value = _state.value.copy(paying = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.pay(branchId, key, body)) {
                is ApiResult.Success -> {
                    intent = null
                    val payment = r.value
                    _state.value = if (payment.status == "PENDING") {
                        // Tarjeta o transferencia: falta lo que diga la terminal o el banco.
                        _state.value.copy(paying = null, recording = ResultState(payment, brand = null))
                    } else {
                        val change = payment.change?.let { amountOf(it) }?.takeIf { it.signum() > 0 }
                        _state.value.copy(
                            paying = null,
                            notice = if (change != null) Notice(R.string.pay_done_change, change.toPlainString()) else Notice(R.string.pay_done),
                        )
                    }
                }
                is ApiResult.Failure -> {
                    // Sin respuesta, el diálogo conserva todo y reintentar usa la misma clave: no cobra dos veces.
                    if (!isRetryable(r.failure)) intent = null
                    _state.value = _state.value.copy(paying = _state.value.paying?.copy(submitting = false, failure = r.failure))
                }
            }
            load()
        }
    }

    // --- Resultado de la terminal ---

    fun startResult(payment: PaymentDto) {
        if (payment.status != "PENDING" || _state.value.busy) return
        _state.value = _state.value.copy(recording = ResultState(payment), actionFailure = null, notice = null)
    }

    private fun editResult(change: (ResultState) -> ResultState) {
        val current = _state.value.recording ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(recording = change(current).copy(failure = null))
    }

    fun setBrand(brand: String) = editResult { it.copy(brand = brand) }

    fun setReference(text: String) = editResult { it.copy(reference = text.take(120)) }

    fun setLast4(text: String) = editResult { it.copy(last4 = text.filter(Char::isDigit).take(4)) }

    fun dismissResult() {
        if (_state.value.recording?.submitting == true) return
        _state.value = _state.value.copy(recording = null)
    }

    /** CONFIRMED, FAILED, CANCELLED o UNKNOWN; UNKNOWN deja el pago para que lo resuelva un gerente (P-4). */
    fun recordResult(outcome: String) {
        val current = _state.value.recording ?: return
        if (if (outcome == "CONFIRMED") !current.canConfirm else !current.canReject) return
        val reference = current.reference.trim().takeIf { outcome == "CONFIRMED" }
        val card = if (outcome == "CONFIRMED" && current.isCard && (current.brand != null || current.last4.isNotEmpty())) {
            CardDto(brand = current.brand, last4 = current.last4.ifEmpty { null })
        } else {
            null
        }
        val key = keyFor(Triple(current.payment.id, outcome, reference to card))
        _state.value = _state.value.copy(recording = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.recordResult(branchId, current.payment, key, outcome, reference, card)) {
                is ApiResult.Success -> {
                    intent = null
                    _state.value = _state.value.copy(recording = null, notice = Notice(resultNotice(outcome)))
                }
                is ApiResult.Failure -> {
                    val retry = isRetryable(r.failure)
                    if (!retry) intent = null
                    val conflict = (r.failure as? ApiFailure.Server)?.status == 409
                    // Otro empleado ya registró el resultado: el diálogo ya no corresponde.
                    _state.value = if (conflict) {
                        _state.value.copy(recording = null, actionFailure = r.failure)
                    } else {
                        _state.value.copy(recording = _state.value.recording?.copy(submitting = false, failure = r.failure))
                    }
                }
            }
            load()
        }
    }

    // --- Mover unidades y dividir ---

    /** La versión más reciente que se tiene de una cuenta: el sondeo pudo actualizarla. */
    private fun latest(check: CheckDto): CheckDto = _state.value.checks.firstOrNull { it.id == check.id } ?: check

    fun startMove(check: CheckDto) {
        if (!canMoveFrom(check) || _state.value.busy) return
        // Si ya hay otra cuenta abierta, es el destino más probable; si no, una nueva.
        val target = moveTargets(_state.value.checks, check).lastOrNull()?.id
        _state.value = _state.value.copy(moving = MoveUnitsState(check, targetId = target), actionFailure = null, notice = null)
    }

    private fun editMove(change: (MoveUnitsState) -> MoveUnitsState) {
        val current = _state.value.moving ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(moving = change(current).copy(failure = null))
    }

    fun toggleUnit(unitId: String) = editMove { it.copy(selected = if (unitId in it.selected) it.selected - unitId else it.selected + unitId) }

    /** null: cuenta nueva. */
    fun chooseTarget(checkId: String?) = editMove { it.copy(targetId = checkId) }

    fun setNewLabel(text: String) = editMove { it.copy(label = text.take(60)) }

    fun dismissMove() {
        if (_state.value.moving?.submitting == true) return
        _state.value = _state.value.copy(moving = null)
    }

    fun confirmMove() {
        val current = _state.value.moving ?: return
        if (!current.canSubmit) return
        _state.value = _state.value.copy(moving = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            var target = current.targetId?.let { id -> _state.value.checks.firstOrNull { it.id == id } } ?: current.created
            if (target == null) {
                // La cuenta nueva se crea una sola vez: un reintento usa la misma clave y la misma cuenta.
                val key = keyFor(Pair("NEW_CHECK", current.source.id))
                when (val r = repository.create(branchId, key, sessionId, current.label.trim().ifEmpty { null })) {
                    is ApiResult.Success -> {
                        intent = null
                        target = r.value
                        _state.value = _state.value.copy(moving = _state.value.moving?.copy(created = r.value))
                    }
                    is ApiResult.Failure -> {
                        _state.value = _state.value.copy(moving = _state.value.moving?.copy(submitting = false, failure = r.failure))
                        return@launch
                    }
                }
            }
            val source = latest(current.source)
            when (val r = repository.move(branchId, source, current.selected.toList(), latest(target))) {
                is ApiResult.Success -> _state.value = _state.value.copy(moving = null, notice = Notice(R.string.move_done))
                is ApiResult.Failure -> {
                    // La cuenta nueva ya existe: el reintento la usa como destino.
                    val created = _state.value.moving?.created
                    _state.value = _state.value.copy(
                        moving = _state.value.moving?.copy(submitting = false, failure = r.failure, targetId = created?.id ?: current.targetId),
                    )
                }
            }
            load()
        }
    }

    // --- Reabrir, cancelar y cortesías ---

    fun startAction(kind: CheckActionKind, check: CheckDto, unit: com.restia.mobile.core.network.CheckUnitDto? = null) {
        if (_state.value.busy) return
        val allowed = when (kind) {
            CheckActionKind.REOPEN -> canReopen(check)
            CheckActionKind.CANCEL -> canCancelCheck(check)
            // Sin unidad, el diálogo la pide; con unidad, debe poder recibir cortesía.
            CheckActionKind.COURTESY -> canAdjust(check) && (if (unit == null) courtesyCandidates(check).isNotEmpty() else unit in courtesyCandidates(check))
            CheckActionKind.REVERT -> canAdjust(check) && unit?.adjustment != null
        }
        if (!allowed) return
        val needsApproval = when (kind) {
            CheckActionKind.REOPEN -> !canReopenDirectly
            CheckActionKind.COURTESY, CheckActionKind.REVERT -> !canAdjustDirectly
            CheckActionKind.CANCEL -> false
        }
        _state.value = _state.value.copy(action = CheckAction(kind, check, unit, needsApproval), actionFailure = null, notice = null)
    }

    private fun editAction(change: (CheckAction) -> CheckAction) {
        val current = _state.value.action ?: return
        if (current.submitting) return
        _state.value = _state.value.copy(action = change(current).copy(failure = null))
    }

    fun setActionReason(text: String) = editAction { it.copy(reason = text.take(500)) }

    /** Cortesía: el platillo; un token ya pedido era para otro, así que deja de servir. */
    fun chooseCourtesyUnit(unit: com.restia.mobile.core.network.CheckUnitDto) = editAction { it.copy(unit = unit) }

    fun setApproverEmail(text: String) = editAction { it.copy(approver = it.approver.copy(email = text.take(254))) }

    fun setApproverPassword(text: String) = editAction { it.copy(approver = it.approver.copy(password = text.take(1024))) }

    fun dismissAction() {
        if (_state.value.action?.submitting == true) return
        _state.value = _state.value.copy(action = null)
    }

    fun confirmAction() {
        val current = _state.value.action ?: return
        if (!current.canSubmit) return
        val reason = current.reason.trim()
        _state.value = _state.value.copy(action = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            var approval = current.approval?.takeIf { current.approved && now() < it.validUntil }
            if (current.needsApproval && approval == null) {
                val r = repository.approve(branchId, current.kind.approvalAction!!, current.resourceIds, reason, current.approver.email.trim(), current.approver.password)
                when (r) {
                    is ApiResult.Success -> {
                        val margin = minOf(5, r.value.expiresIn / 2) * 1000L
                        approval = GrantedApproval(r.value.approvalToken, current.resourceIds, reason, now() + r.value.expiresIn * 1000L - margin)
                        // Con el token ya no hace falta la contraseña en memoria.
                        _state.value = _state.value.copy(action = current.copy(submitting = true, approval = approval, approver = current.approver.copy(password = "")))
                    }
                    is ApiResult.Failure -> {
                        val keep = if (isRetryable(r.failure)) current.approver.password else ""
                        _state.value = _state.value.copy(action = current.copy(approver = current.approver.copy(password = keep), failure = r.failure))
                        return@launch
                    }
                }
            }
            val check = latest(current.check)
            val token = approval?.token
            val r = when (current.kind) {
                CheckActionKind.REOPEN -> repository.reopen(branchId, check, reason, token)
                CheckActionKind.CANCEL -> repository.cancel(branchId, check, reason)
                CheckActionKind.COURTESY -> repository.courtesy(branchId, check, current.unit!!.unitId, reason, token)
                CheckActionKind.REVERT -> repository.revertCourtesy(branchId, check, current.unit!!.adjustment!!.id, reason, token)
            }
            val sent = _state.value.action ?: current
            when (r) {
                is ApiResult.Success -> _state.value = _state.value.copy(action = null, notice = Notice(actionNotice(current.kind)))
                is ApiResult.Failure -> {
                    val code = (r.failure as? ApiFailure.Server)?.code
                    // Un token vencido o ya usado no sirve otra vez; con la red caída se conserva para reintentar.
                    val keepApproval = isRetryable(r.failure) && code != "APPROVAL_INVALID"
                    _state.value = _state.value.copy(
                        action = sent.copy(submitting = false, failure = r.failure, approval = if (keepApproval) sent.approval else null),
                    )
                }
            }
            load()
        }
    }

    @StringRes
    private fun actionNotice(kind: CheckActionKind): Int = when (kind) {
        CheckActionKind.REOPEN -> R.string.check_reopened
        CheckActionKind.CANCEL -> R.string.check_cancelled_done
        CheckActionKind.COURTESY -> R.string.courtesy_done
        CheckActionKind.REVERT -> R.string.courtesy_reverted
    }

    @StringRes
    private fun resultNotice(outcome: String): Int = when (outcome) {
        "CONFIRMED" -> R.string.pay_done
        "FAILED" -> R.string.result_failed_done
        "CANCELLED" -> R.string.result_cancelled_done
        else -> R.string.result_unknown_done
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

    class Factory(
        private val repository: CheckRepository,
        private val branchId: String,
        private val sessionId: String,
        private val canReopenDirectly: Boolean = false,
        private val canAdjustDirectly: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CheckViewModel(repository, branchId, sessionId, canReopenDirectly = canReopenDirectly, canAdjustDirectly = canAdjustDirectly) as T
    }

    companion object {
        const val PRE_CHECK = "PRE_CHECK"
        const val PAYMENT_RECEIPT = "PAYMENT_RECEIPT"
        private const val POLL_MILLIS = 15_000L
    }
}
