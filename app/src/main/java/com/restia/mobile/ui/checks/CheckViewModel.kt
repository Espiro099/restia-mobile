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

data class PayState(
    val check: CheckDto,
    val shiftId: String?,
    val method: String = CASH,
    val amount: String,
    val tip: String = "",
    val tendered: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val amountValue: BigDecimal? get() = parseAmount(amount)?.takeIf { it.signum() > 0 }
    val tipValue: BigDecimal? get() = if (tip.isBlank()) BigDecimal.ZERO.setScale(2) else parseAmount(tip)
    val tenderedValue: BigDecimal? get() = parseAmount(tendered)
    val exceeds: Boolean get() = amountValue?.let { it > available(check) } ?: false

    /** Cambio en efectivo; null si todavía no se puede calcular o no alcanza. */
    val change: BigDecimal?
        get() {
            val due = (amountValue ?: return null) + (tipValue ?: return null)
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
    val paying: PayState? = null,
    val recording: ResultState? = null,
    val working: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val notice: Notice? = null,
) {
    val busy: Boolean get() = working || paying?.submitting == true || recording?.submitting == true
}

class CheckViewModel(
    private val repository: CheckRepository,
    private val branchId: String,
    private val sessionId: String,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(CheckUiState())
    val state: StateFlow<CheckUiState> = _state

    private var polling: Job? = null

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

    fun print(type: String, sourceId: String) {
        if (_state.value.busy) return
        val key = keyFor(Pair(type, sourceId))
        _state.value = _state.value.copy(working = true, actionFailure = null, notice = null)
        viewModelScope.launch {
            val r = repository.print(branchId, key, type, sourceId)
            val failure = (r as? ApiResult.Failure)?.failure
            // Impreso o rechazado, otro toque es otra impresión; sin respuesta, el reintento no la duplica.
            if (failure == null || !isRetryable(failure)) intent = null
            _state.value = _state.value.copy(
                working = false,
                actionFailure = failure,
                notice = if (failure == null) Notice(if (type == PRE_CHECK) R.string.print_pre_check_sent else R.string.print_receipt_sent) else null,
            )
        }
    }

    // --- Cobrar ---

    fun startPay(check: CheckDto) {
        if (!isPayable(check) || _state.value.busy) return
        val amount = available(check).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val shifts = _state.value.shifts
        _state.value = _state.value.copy(
            paying = PayState(check, shiftId = shifts?.singleOrNull()?.id, amount = amount),
            actionFailure = null,
            notice = null,
        )
        if (shifts == null) loadShifts()
    }

    private fun loadShifts() {
        viewModelScope.launch {
            when (val r = repository.openShifts(branchId)) {
                is ApiResult.Success -> {
                    val paying = _state.value.paying
                    _state.value = _state.value.copy(
                        shifts = r.value,
                        shiftsFailure = null,
                        // Con una sola caja abierta no hay nada que elegir.
                        paying = paying?.copy(shiftId = paying.shiftId ?: r.value.singleOrNull()?.id),
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(shiftsFailure = r.failure)
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

    fun setTip(text: String) = editPay { it.copy(tip = text.take(16)) }

    fun setTendered(text: String) = editPay { it.copy(tendered = text.take(16)) }

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

    @StringRes
    private fun resultNotice(outcome: String): Int = when (outcome) {
        "CONFIRMED" -> R.string.pay_done
        "FAILED" -> R.string.result_failed_done
        "CANCELLED" -> R.string.result_cancelled_done
        else -> R.string.result_unknown_done
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

    class Factory(private val repository: CheckRepository, private val branchId: String, private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CheckViewModel(repository, branchId, sessionId) as T
    }

    companion object {
        const val PRE_CHECK = "PRE_CHECK"
        const val PAYMENT_RECEIPT = "PAYMENT_RECEIPT"
        private const val POLL_MILLIS = 15_000L
    }
}
