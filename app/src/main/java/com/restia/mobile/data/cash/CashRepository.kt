package com.restia.mobile.data.cash

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CashMovementCreateRequest
import com.restia.mobile.core.network.CashMovementDto
import com.restia.mobile.core.network.CashRegisterDto
import com.restia.mobile.core.network.CashShiftCloseRequest
import com.restia.mobile.core.network.CashShiftDto
import com.restia.mobile.core.network.CashShiftOpenRequest
import com.restia.mobile.core.network.CashShiftSummaryDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PagedEnvelope
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import java.math.BigDecimal
import java.math.RoundingMode

/** Restia opera en una sola moneda (el backend fija MXN). */
const val CASH_CURRENCY = "MXN"

fun money(amount: BigDecimal, currency: String = CASH_CURRENCY) = MoneyDto(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency)

/** Una caja activa de la sucursal con su turno vigente (OPEN o CLOSING), o sin turno. */
data class RegisterShift(val register: CashRegisterDto, val shift: CashShiftDto?)

/** Turnos de caja (Decisiones_Cash): abrir, depósitos y retiros, y el corte en dos pasos. */
class CashRepository(private val api: SessionApi) {
    /** Cajas activas, por nombre, cada una con su turno vigente. Una caja solo admite un turno OPEN o CLOSING. */
    suspend fun overview(branchId: String): ApiResult<List<RegisterShift>> = apiCall {
        val registers = all { page -> api.listCashRegisters(branchId, page, PER_PAGE) }.filter { it.status == "ACTIVE" }
        val shifts = (ACTIVE_STATUSES.flatMap { status -> all { page -> api.listCashShifts(branchId, status, page, PER_PAGE) } })
            .filter { it.status in ACTIVE_STATUSES }
            .associateBy { it.cashRegisterId }
        registers.map { RegisterShift(it, shifts[it.id]) }.sortedBy { it.register.name.lowercase() }
    }

    suspend fun open(branchId: String, idempotencyKey: String, registerId: String, amount: BigDecimal, reason: String?): ApiResult<CashShiftDto> =
        apiCall { api.openCashShift(branchId, idempotencyKey, CashShiftOpenRequest(registerId, money(amount), reason)).data }

    suspend fun shift(branchId: String, shiftId: String): ApiResult<CashShiftDto> = apiCall { api.getCashShift(branchId, shiftId).data }

    suspend fun summary(branchId: String, shiftId: String): ApiResult<CashShiftSummaryDto> = apiCall { api.cashShiftSummary(branchId, shiftId).data }

    /** Del más reciente al más antiguo. */
    suspend fun movements(branchId: String, shiftId: String): ApiResult<List<CashMovementDto>> = apiCall {
        all { page -> api.listCashMovements(branchId, shiftId, page, PER_PAGE) }.sortedByDescending { it.createdAt }
    }

    suspend fun move(branchId: String, shiftId: String, idempotencyKey: String, type: String, amount: BigDecimal, reason: String): ApiResult<CashMovementDto> =
        apiCall { api.createCashMovement(branchId, shiftId, idempotencyKey, CashMovementCreateRequest(type, money(amount), reason)).data }

    suspend fun startClosing(branchId: String, shift: CashShiftDto): ApiResult<CashShiftDto> =
        apiCall { api.startCashShiftClosing(branchId, shift.id, "\"${shift.version}\"").data }

    suspend fun cancelClosing(branchId: String, shift: CashShiftDto, reason: String): ApiResult<CashShiftDto> =
        apiCall { api.cancelCashShiftClosing(branchId, shift.id, "\"${shift.version}\"", ReasonRequest(reason)).data }

    suspend fun close(
        branchId: String,
        shift: CashShiftDto,
        idempotencyKey: String,
        counted: BigDecimal,
        leftInDrawer: BigDecimal,
        reason: String?,
    ): ApiResult<CashShiftDto> = apiCall {
        api.closeCashShift(branchId, shift.id, "\"${shift.version}\"", idempotencyKey, CashShiftCloseRequest(money(counted), money(leftInDrawer), reason)).data
    }

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
        val ACTIVE_STATUSES = listOf("OPEN", "CLOSING")
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
