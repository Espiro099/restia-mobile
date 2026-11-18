package com.restia.mobile.data.devices

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.DeviceDto
import com.restia.mobile.core.network.EnrollmentCodeDto
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError
import retrofit2.Response

/** Cómo se cierra un dispositivo: los dos son finales y cierran sus sesiones. */
enum class DeviceClosing { BLOCK, RETIRE }

/**
 * La lista y cuál es este teléfono. [thisDevice] sale de la sesión actual: el `deviceUuid` que guarda la app
 * al darse de alta no es el `id` que muestra la lista.
 */
data class DeviceList(val devices: List<DeviceDto>, val thisDevice: String?)

/** Teléfonos y tabletas dados de alta en la sucursal. */
class DevicesRepository(private val api: SessionApi) {
    /** Este teléfono arriba, luego los activos y al final los cerrados; dentro de cada grupo, el orden del servidor (alta más reciente). */
    suspend fun all(branchId: String): ApiResult<DeviceList> = apiCall {
        val rows = mutableListOf<DeviceDto>()
        var page = 1
        do {
            val response = api.listDevices(branchId, page, PER_PAGE)
            rows += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        val current = api.listOwnSessions(ACTIVE, 1, SESSIONS_PER_PAGE).data.firstOrNull { it.current }?.deviceId
        val order = { d: DeviceDto ->
            when {
                d.id.equals(current, ignoreCase = true) -> 0
                d.status == ACTIVE -> 1
                else -> 2
            }
        }
        DeviceList(rows.sortedBy(order), current)
    }

    suspend fun enrollmentCode(branchId: String): ApiResult<EnrollmentCodeDto> = apiCall { api.createEnrollmentCode(branchId).data }

    suspend fun close(branchId: String, deviceId: String, how: DeviceClosing, reason: String): ApiResult<Unit> {
        val body = ReasonRequest(reason.trim())
        val r = apiCall {
            when (how) {
                DeviceClosing.BLOCK -> api.blockDevice(branchId, deviceId, body)
                DeviceClosing.RETIRE -> api.retireDevice(branchId, deviceId, body)
            }
        }
        return when (r) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> r.value.toUnit()
        }
    }

    private fun Response<Unit>.toUnit(): ApiResult<Unit> =
        if (isSuccessful) ApiResult.Success(Unit) else ApiResult.Failure(parseError(code(), errorBody()?.string(), headers()["Retry-After"]))

    companion object {
        const val ACTIVE = "ACTIVE"
        const val BLOCKED = "BLOCKED"
        const val RETIRED = "RETIRED"
        private const val PER_PAGE = 100
        private const val MAX_PAGES = 5
        private const val SESSIONS_PER_PAGE = 50
    }
}
