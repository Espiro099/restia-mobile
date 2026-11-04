package com.restia.mobile.data.orders

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderCreateRequest
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

class OrderRepository(private val api: SessionApi) {
    suspend fun create(branchId: String, idempotencyKey: String, body: OrderCreateRequest): ApiResult<OrderDto> =
        apiCall { api.createOrder(branchId, idempotencyKey, body).data }
}
