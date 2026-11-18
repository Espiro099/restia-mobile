package com.restia.mobile.data.account

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.AuthSessionDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError

/** Las sesiones abiertas del usuario, para cerrar las que ya no usa. */
class SessionsRepository(private val api: SessionApi) {
    /** Solo las activas; la actual primero y luego por actividad más reciente. */
    suspend fun active(): ApiResult<List<AuthSessionDto>> = apiCall {
        val rows = mutableListOf<AuthSessionDto>()
        var page = 1
        do {
            val response = api.listOwnSessions("ACTIVE", page, PER_PAGE)
            rows += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        rows.sortedWith(compareByDescending<AuthSessionDto> { it.current }.thenByDescending { it.lastActivityAt })
    }

    /** Una ya cerrada o vencida (404) se da por cerrada: el resultado es el mismo que se buscaba. */
    suspend fun revoke(id: String): ApiResult<Unit> =
        when (val r = apiCall { api.revokeOwnSession(id) }) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> when {
                r.value.isSuccessful || r.value.code() == 404 -> ApiResult.Success(Unit)
                else -> ApiResult.Failure(parseError(r.value.code(), r.value.errorBody()?.string(), r.value.headers()["Retry-After"]))
            }
        }

    private companion object {
        const val PER_PAGE = 50
        const val MAX_PAGES = 5
    }
}
