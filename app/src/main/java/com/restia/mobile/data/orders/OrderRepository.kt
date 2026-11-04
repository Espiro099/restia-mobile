package com.restia.mobile.data.orders

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderCreateRequest
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.UnitVersion
import com.restia.mobile.core.network.UnitTransitionRequest
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.Response

class OrderRepository(private val api: SessionApi) {
    private var known: List<OrderDto>? = null
    private var knownSession: String? = null
    private var etag: String? = null

    /** Las rondas de la sesión, de la más antigua a la más reciente. */
    suspend fun listForSession(branchId: String, sessionId: String): ApiResult<List<OrderDto>> {
        if (knownSession != sessionId) {
            known = null
            etag = null
            knownSession = sessionId
        }
        return try {
            val first = api.listOrders(branchId, sessionId, 1, PER_PAGE, etag.takeIf { known != null })
            val cached = known
            if (first.code() == 304 && cached != null) return ApiResult.Success(cached)
            if (!first.isSuccessful) return ApiResult.Failure(serverFailure(first))
            val body = first.body() ?: return ApiResult.Failure(ApiFailure.Unexpected)
            val orders = body.data.toMutableList()
            val lastPage = minOf(body.meta.pagination?.lastPage ?: 1, MAX_PAGES)
            for (page in 2..lastPage) {
                val next = api.listOrders(branchId, sessionId, page, PER_PAGE, null)
                if (!next.isSuccessful) return ApiResult.Failure(serverFailure(next))
                orders += next.body()?.data.orEmpty()
            }
            // Un ETag por página no describe toda la lista: solo se reutiliza si cupo en una.
            etag = first.headers()["ETag"].takeIf { lastPage == 1 }
            val sorted = orders.sortedBy { it.createdAt.orEmpty() }
            known = sorted
            ApiResult.Success(sorted)
        } catch (_: IOException) {
            ApiResult.Failure(ApiFailure.Network)
        } catch (_: SerializationException) {
            ApiResult.Failure(ApiFailure.Unexpected)
        }
    }

    suspend fun deliver(branchId: String, idempotencyKey: String, units: List<UnitVersion>): ApiResult<Unit> = apiCall {
        api.transitionUnits(branchId, idempotencyKey, UnitTransitionRequest("DELIVER", units)).close()
    }

    private fun serverFailure(response: Response<*>) =
        parseError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])

    suspend fun create(branchId: String, idempotencyKey: String, body: OrderCreateRequest): ApiResult<OrderDto> =
        apiCall { api.createOrder(branchId, idempotencyKey, body).data }

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 20
    }
}
