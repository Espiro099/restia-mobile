package com.restia.mobile.data.orders

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError

/** Conserva el último menú y su ETag en memoria. La caché en disco de A-7 sigue pendiente. */
class MenuRepository(private val api: SessionApi) {
    private var menu: MenuDto? = null
    private var etag: String? = null

    suspend fun load(branchId: String): ApiResult<MenuDto> {
        val result = apiCall { api.getMenu(branchId, etag.takeIf { menu != null }) }
        val response = when (result) {
            is ApiResult.Failure -> return menu?.let { ApiResult.Success(it) } ?: result
            is ApiResult.Success -> result.value
        }
        val body = response.body()?.data
        return when {
            response.code() == 304 -> menu?.let { ApiResult.Success(it) } ?: ApiResult.Failure(parseError(304, null, null))
            response.isSuccessful && body != null -> {
                menu = body
                etag = response.headers()["ETag"]
                ApiResult.Success(body)
            }
            else -> ApiResult.Failure(
                parseError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"]),
            )
        }
    }
}
