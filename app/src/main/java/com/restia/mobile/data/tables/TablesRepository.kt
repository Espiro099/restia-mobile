package com.restia.mobile.data.tables

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
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

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
