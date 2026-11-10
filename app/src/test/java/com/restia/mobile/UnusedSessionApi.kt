package com.restia.mobile

import com.restia.mobile.core.network.ApprovalData
import com.restia.mobile.core.network.ApprovalRequest
import com.restia.mobile.core.network.CleaningConfirmationRequest
import com.restia.mobile.core.network.Envelope
import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.core.network.StationDto
import com.restia.mobile.core.network.MeData
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.OrderCreateRequest
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.PagedEnvelope
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.network.TableSessionDto
import com.restia.mobile.core.network.TableSessionOpenRequest
import com.restia.mobile.core.network.UnitTransitionRequest
import okhttp3.ResponseBody
import retrofit2.Response

/** Base para fakes de la API: cada prueba sobrescribe solo lo que usa. */
open class UnusedSessionApi : SessionApi {
    override suspend fun me(): Envelope<MeData> = error("sin uso")
    override suspend fun logout(): Response<Unit> = error("sin uso")
    override suspend fun listTables(branchId: String, page: Int, perPage: Int): PagedEnvelope<TableDto> = error("sin uso")
    override suspend fun getMenu(branchId: String, etag: String?): Response<Envelope<MenuDto>> = error("sin uso")
    override suspend fun listOrders(branchId: String, sessionId: String, page: Int, perPage: Int, etag: String?): Response<PagedEnvelope<OrderDto>> =
        error("sin uso")
    override suspend fun transitionUnits(branchId: String, idempotencyKey: String, body: UnitTransitionRequest, approvalToken: String?): ResponseBody =
        error("sin uso")
    override suspend fun createApproval(branchId: String, body: ApprovalRequest): Envelope<ApprovalData> = error("sin uso")
    override suspend fun createOrder(branchId: String, idempotencyKey: String, body: OrderCreateRequest): Envelope<OrderDto> = error("sin uso")
    override suspend fun openTableSession(branchId: String, idempotencyKey: String, body: TableSessionOpenRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun listStations(branchId: String, page: Int, perPage: Int): PagedEnvelope<StationDto> = error("sin uso")
    override suspend fun listQueue(branchId: String, stationId: String?, page: Int, perPage: Int, etag: String?): Response<PagedEnvelope<KitchenUnitDto>> =
        error("sin uso")
    override suspend fun getTableSession(branchId: String, sessionId: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun closeTableSession(branchId: String, sessionId: String, ifMatch: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun confirmCleaning(branchId: String, sessionId: String, tableId: String, body: CleaningConfirmationRequest): Envelope<TableSessionDto> =
        error("sin uso")
}
