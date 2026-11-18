package com.restia.mobile.data.orders

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.ProductAvailabilityRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError
import com.restia.mobile.core.storage.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** [stale] indica que viene de caché porque no se pudo consultar al servidor (A-7). */
data class MenuLoad(val menu: MenuDto, val stale: Boolean)

@Serializable
private data class CachedMenu(val menu: MenuDto, val etag: String? = null)

class MenuRepository(private val api: SessionApi, private val cache: BlobStore? = null) {
    private var cached: CachedMenu? = null

    suspend fun load(branchId: String): ApiResult<MenuLoad> {
        val known = cached ?: readCache(branchId)?.also { cached = it }
        val result = apiCall { api.getMenu(branchId, known?.etag.takeIf { known != null }) }
        val response = when (result) {
            is ApiResult.Failure -> return known?.let { ApiResult.Success(MenuLoad(it.menu, stale = true)) } ?: result
            is ApiResult.Success -> result.value
        }
        val body = response.body()?.data
        return when {
            response.code() == 304 -> known?.let { ApiResult.Success(MenuLoad(it.menu, stale = false)) }
                ?: ApiResult.Failure(parseError(304, null, null))
            response.isSuccessful && body != null -> {
                val fresh = CachedMenu(body, response.headers()["ETag"])
                cached = fresh
                withContext(Dispatchers.IO) { cache?.write(name(branchId), ApiJson.encodeToString(CachedMenu.serializer(), fresh)) }
                ApiResult.Success(MenuLoad(body, stale = false))
            }
            else -> {
                val failure = parseError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])
                if (known != null && failure.status >= 500) ApiResult.Success(MenuLoad(known.menu, stale = true)) else ApiResult.Failure(failure)
            }
        }
    }

    /**
     * Marca un platillo agotado o disponible. La copia local se actualiza al momento y pierde su ETag, así
     * que la siguiente carga trae la carta completa del servidor.
     */
    suspend fun setAvailability(branchId: String, productId: String, available: Boolean): ApiResult<Unit> {
        val value = if (available) "AVAILABLE" else "SOLD_OUT"
        val r = apiCall { api.setProductAvailability(branchId, productId, ProductAvailabilityRequest(value)).close() }
        if (r is ApiResult.Success) {
            cached?.let { known ->
                val menu = known.menu.copy(products = known.menu.products.map { if (it.id == productId) it.copy(availability = value) else it })
                cached = CachedMenu(menu, etag = null)
            }
        }
        return r
    }

    private suspend fun readCache(branchId: String): CachedMenu? = withContext(Dispatchers.IO) {
        cache?.read(name(branchId))?.let {
            try {
                ApiJson.decodeFromString(CachedMenu.serializer(), it)
            } catch (_: SerializationException) {
                null
            }
        }
    }

    private fun name(branchId: String) = "menu-$branchId"
}
