package com.restia.mobile.data.tables

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.network.TableSessionDto
import com.restia.mobile.core.network.TableSessionOpenRequest
import com.restia.mobile.core.network.apiCall

class TablesRepository(private val api: SessionApi) {
    /** Recorre todas las páginas, porque la sucursal se muestra completa. */
    suspend fun list(branchId: String): ApiResult<List<TableDto>> = apiCall {
        val tables = mutableListOf<TableDto>()
        var page = 1
        do {
            val response = api.listTables(branchId, page, PER_PAGE)
            tables += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        tables.sortedBy { it.number }
    }

    suspend fun openSession(
        branchId: String,
        tableId: String,
        guestCount: Int,
        acknowledgeReservation: Boolean,
        idempotencyKey: String,
    ): ApiResult<TableSessionDto> = apiCall {
        val body = TableSessionOpenRequest(listOf(tableId), guestCount, acknowledgeReservation.takeIf { it })
        api.openTableSession(branchId, idempotencyKey, body).data
    }

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
