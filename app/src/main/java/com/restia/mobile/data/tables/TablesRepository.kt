package com.restia.mobile.data.tables

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.network.TableSessionDto
import com.restia.mobile.core.network.TableSessionOpenRequest
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.storage.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

class TablesRepository(private val api: SessionApi, private val cache: BlobStore? = null) {
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
        tables.sortedBy { it.number }.also { saveCache(branchId, it) }
    }

    /** Última lista conocida, para consultar sin conexión (A-7). */
    suspend fun cached(branchId: String): List<TableDto>? {
        val store = cache ?: return null
        return withContext(Dispatchers.IO) {
            store.read(name(branchId))?.let {
                try {
                    ApiJson.decodeFromString(ListSerializer(TableDto.serializer()), it)
                } catch (_: SerializationException) {
                    null
                }
            }
        }
    }

    private suspend fun saveCache(branchId: String, tables: List<TableDto>) {
        val store = cache ?: return
        withContext(Dispatchers.IO) {
            store.write(name(branchId), ApiJson.encodeToString(ListSerializer(TableDto.serializer()), tables))
        }
    }

    private fun name(branchId: String) = "tables-$branchId"

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
