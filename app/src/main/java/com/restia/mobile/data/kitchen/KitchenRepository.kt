package com.restia.mobile.data.kitchen

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.StationDto
import com.restia.mobile.core.network.UnitTransitionRequest
import com.restia.mobile.core.network.UnitVersion
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.Response

class KitchenRepository(private val api: SessionApi) {
    private var known: List<KitchenUnitDto>? = null
    private var knownStation: String? = null
    private var etag: String? = null

    /** Estaciones activas, para filtrar la cola. */
    suspend fun stations(branchId: String): ApiResult<List<StationDto>> = apiCall {
        val stations = mutableListOf<StationDto>()
        var page = 1
        do {
            val response = api.listStations(branchId, page, PER_PAGE)
            stations += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        stations.filter { it.status == "ACTIVE" }.sortedBy { it.name }
    }

    /** Lo que la estación tiene que atender: pendiente, en preparación y listo, del más antiguo al más reciente. */
    suspend fun queue(branchId: String, stationId: String?): ApiResult<List<KitchenUnitDto>> {
        if (knownStation != stationId) {
            known = null
            etag = null
            knownStation = stationId
        }
        return try {
            val first = api.listQueue(branchId, stationId, 1, PER_PAGE, etag.takeIf { known != null })
            val cached = known
            if (first.code() == 304 && cached != null) return ApiResult.Success(cached)
            if (!first.isSuccessful) return ApiResult.Failure(serverFailure(first))
            val body = first.body() ?: return ApiResult.Failure(ApiFailure.Unexpected)
            val units = body.data.toMutableList()
            val lastPage = minOf(body.meta.pagination?.lastPage ?: 1, MAX_PAGES)
            for (page in 2..lastPage) {
                val next = api.listQueue(branchId, stationId, page, PER_PAGE, null)
                if (!next.isSuccessful) return ApiResult.Failure(serverFailure(next))
                units += next.body()?.data.orEmpty()
            }
            // Un ETag por página no describe toda la cola: solo se reutiliza si cupo en una.
            etag = first.headers()["ETag"].takeIf { lastPage == 1 }
            known = units
            ApiResult.Success(units)
        } catch (_: IOException) {
            ApiResult.Failure(ApiFailure.Network)
        } catch (_: SerializationException) {
            ApiResult.Failure(ApiFailure.Unexpected)
        }
    }

    /** PENDING → PREPARING. Al empezar, el backend descuenta el inventario de la receta. */
    suspend fun start(branchId: String, idempotencyKey: String, units: List<UnitVersion>): ApiResult<Unit> =
        transition(branchId, idempotencyKey, UnitTransitionRequest("START", units))

    /** PREPARING → READY: el mesero ya puede llevarlo. */
    suspend fun ready(branchId: String, idempotencyKey: String, units: List<UnitVersion>): ApiResult<Unit> =
        transition(branchId, idempotencyKey, UnitTransitionRequest("READY", units))

    private suspend fun transition(branchId: String, idempotencyKey: String, body: UnitTransitionRequest): ApiResult<Unit> =
        apiCall { api.transitionUnits(branchId, idempotencyKey, body).close() }

    private fun serverFailure(response: Response<*>) =
        parseError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
