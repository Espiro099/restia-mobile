package com.restia.mobile.data.checks

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CardDto
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.PagedEnvelope
import com.restia.mobile.core.network.PaymentCreateRequest
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.PaymentResultRequest
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.core.network.PrintJobRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

/** Turno abierto donde se registra el cobro, con el nombre de su caja. */
data class ShiftOption(val id: String, val registerName: String)

class CheckRepository(private val api: SessionApi) {
    /** Cuentas de la sesión, de la primera a la última. */
    suspend fun checks(branchId: String, sessionId: String): ApiResult<List<CheckDto>> = apiCall {
        all { page -> api.listChecks(branchId, sessionId, page, PER_PAGE) }.sortedBy { it.number }
    }

    suspend fun payments(branchId: String, checkId: String): ApiResult<List<PaymentDto>> = apiCall {
        all { page -> api.listPayments(branchId, checkId, page, PER_PAGE) }.sortedBy { it.createdAt }
    }

    suspend fun request(branchId: String, check: CheckDto): ApiResult<CheckDto> =
        apiCall { api.requestCheck(branchId, check.id, "\"${check.version}\"").data }

    /** Turnos OPEN, cada uno con el nombre de su caja; sin nombre conocido, el código de la caja no se adivina. */
    suspend fun openShifts(branchId: String): ApiResult<List<ShiftOption>> = apiCall {
        val shifts = all { page -> api.listCashShifts(branchId, "OPEN", page, PER_PAGE) }.filter { it.status == "OPEN" }
        val registers = all { page -> api.listCashRegisters(branchId, page, PER_PAGE) }.associateBy { it.id }
        shifts.map { ShiftOption(it.id, registers[it.cashRegisterId]?.name ?: "") }.sortedBy { it.registerName }
    }

    suspend fun pay(branchId: String, idempotencyKey: String, body: PaymentCreateRequest): ApiResult<PaymentDto> =
        apiCall { api.createPayment(branchId, idempotencyKey, body).data }

    suspend fun recordResult(
        branchId: String,
        payment: PaymentDto,
        idempotencyKey: String,
        outcome: String,
        reference: String?,
        card: CardDto?,
    ): ApiResult<PaymentDto> = apiCall {
        api.recordPaymentResult(branchId, payment.id, "\"${payment.version}\"", idempotencyKey, PaymentResultRequest(outcome, reference, card)).data
    }

    /** PRE_CHECK lleva la cuenta; PAYMENT_RECEIPT, el pago. La impresora sale de la ruta configurada. */
    suspend fun print(branchId: String, idempotencyKey: String, type: String, sourceId: String): ApiResult<PrintJobDto> =
        apiCall { api.createPrintJob(branchId, idempotencyKey, PrintJobRequest(type, sourceId)).data }

    private suspend fun <T> all(fetch: suspend (Int) -> PagedEnvelope<T>): List<T> {
        val rows = mutableListOf<T>()
        var page = 1
        do {
            val response = fetch(page)
            rows += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        return rows
    }

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
