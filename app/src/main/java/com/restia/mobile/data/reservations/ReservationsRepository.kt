package com.restia.mobile.data.reservations

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.AvailabilityDto
import com.restia.mobile.core.network.CheckInRequest
import com.restia.mobile.core.network.QueueEntryDto
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.ReservationCreateRequest
import com.restia.mobile.core.network.ReservationDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ReservationsRepository(private val api: SessionApi) {
    /** Reservas que tocan el intervalo, de la más temprana a la más tarde. */
    suspend fun list(branchId: String, from: String, to: String): ApiResult<List<ReservationDto>> = apiCall {
        val rows = mutableListOf<ReservationDto>()
        var page = 1
        do {
            val response = api.listReservations(branchId, from, to, page, PER_PAGE)
            rows += response.data
            val lastPage = response.meta.pagination?.lastPage ?: page
            page++
        } while (page <= lastPage && page <= MAX_PAGES)
        rows.sortedBy { it.startAt }
    }

    /** Clientes que ya llegaron, por orden de llegada (R-8). */
    suspend fun queue(branchId: String): ApiResult<List<QueueEntryDto>> = apiCall { api.waitingQueue(branchId).data }

    suspend fun availability(branchId: String, startAt: String, endAt: String, partySize: Int): ApiResult<AvailabilityDto> =
        apiCall { api.reservationAvailability(branchId, startAt, endAt, partySize).data }

    suspend fun create(branchId: String, idempotencyKey: String, body: ReservationCreateRequest): ApiResult<ReservationDto> =
        apiCall { api.createReservation(branchId, idempotencyKey, body).data }

    suspend fun confirm(branchId: String, r: ReservationDto, key: String) = action(branchId, r, "confirm", key, EMPTY)

    suspend fun arrive(branchId: String, r: ReservationDto, key: String) = action(branchId, r, "arrive", key, EMPTY)

    suspend fun noShow(branchId: String, r: ReservationDto, key: String) = action(branchId, r, "no-show", key, EMPTY)

    suspend fun cancel(branchId: String, r: ReservationDto, key: String, reason: String) =
        action(branchId, r, "cancel", key, ApiJson.encodeToJsonElement(ReasonRequest.serializer(), ReasonRequest(reason)).jsonObject)

    /** Sienta y abre la sesión; la respuesta trae `sessionId`. */
    suspend fun checkIn(branchId: String, r: ReservationDto, key: String, body: CheckInRequest) =
        action(branchId, r, "check-in", key, ApiJson.encodeToJsonElement(CheckInRequest.serializer(), body).jsonObject)

    private suspend fun action(branchId: String, r: ReservationDto, action: String, key: String, body: JsonObject): ApiResult<ReservationDto> =
        apiCall { api.reservationAction(branchId, r.id, action, "\"${r.version}\"", key, body).data }

    private companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 10
        val EMPTY = JsonObject(emptyMap())
    }
}
