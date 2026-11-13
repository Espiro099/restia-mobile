package com.restia.mobile.data.customers

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CustomerCreateRequest
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.core.network.CustomerSearchRequest
import com.restia.mobile.core.network.CustomerUpdateRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

class CustomersRepository(private val api: SessionApi) {
    /** Primera página: la búsqueda es para encontrar a alguien, no para recorrer la base. */
    suspend fun search(branchId: String, body: CustomerSearchRequest): ApiResult<List<CustomerDto>> =
        apiCall { api.searchCustomers(branchId, 1, PER_PAGE, body).data }

    /** Cliente con este teléfono exacto en la sucursal, si existe. */
    suspend fun byPhone(branchId: String, phone: String): ApiResult<CustomerDto?> =
        apiCall { api.searchCustomers(branchId, 1, 1, CustomerSearchRequest(phone = phone)).data.firstOrNull() }

    suspend fun get(branchId: String, id: String): ApiResult<CustomerDto> = apiCall { api.getCustomer(branchId, id).data }

    suspend fun create(branchId: String, idempotencyKey: String, body: CustomerCreateRequest): ApiResult<CustomerDto> =
        apiCall { api.createCustomer(branchId, idempotencyKey, body).data }

    suspend fun update(branchId: String, customer: CustomerDto, body: CustomerUpdateRequest): ApiResult<CustomerDto> =
        apiCall { api.updateCustomer(branchId, customer.id, "\"${customer.version}\"", body).data }

    private companion object {
        const val PER_PAGE = 50
    }
}
