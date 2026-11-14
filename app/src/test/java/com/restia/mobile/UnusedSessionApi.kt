package com.restia.mobile

import com.restia.mobile.core.network.ApprovalData
import com.restia.mobile.core.network.ApprovalRequest
import com.restia.mobile.core.network.CleaningConfirmationRequest
import com.restia.mobile.core.network.CashRegisterDto
import com.restia.mobile.core.network.CashShiftDto
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.AvailabilityDto
import com.restia.mobile.core.network.CustomerCreateRequest
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.core.network.CustomerSearchRequest
import com.restia.mobile.core.network.CustomerUpdateRequest
import com.restia.mobile.core.network.Envelope
import com.restia.mobile.core.network.MemberCreateRequest
import com.restia.mobile.core.network.MemberCreatedDto
import com.restia.mobile.core.network.MemberDto
import com.restia.mobile.core.network.MemberRoleRequest
import com.restia.mobile.core.network.PasswordChangeRequest
import com.restia.mobile.core.network.PasswordSetupLinkDto
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.QueueEntryDto
import com.restia.mobile.core.network.ReservationCreateRequest
import com.restia.mobile.core.network.ReservationDto
import kotlinx.serialization.json.JsonObject
import com.restia.mobile.core.network.PaymentCreateRequest
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.PaymentResultRequest
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.core.network.PrintJobRequest
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
    override suspend fun listChecks(branchId: String, sessionId: String, page: Int, perPage: Int): PagedEnvelope<CheckDto> = error("sin uso")
    override suspend fun requestCheck(branchId: String, checkId: String, ifMatch: String): Envelope<CheckDto> = error("sin uso")
    override suspend fun listCashShifts(branchId: String, status: String, page: Int, perPage: Int): PagedEnvelope<CashShiftDto> = error("sin uso")
    override suspend fun listCashRegisters(branchId: String, page: Int, perPage: Int): PagedEnvelope<CashRegisterDto> = error("sin uso")
    override suspend fun listPayments(branchId: String, checkId: String, page: Int, perPage: Int): PagedEnvelope<PaymentDto> = error("sin uso")
    override suspend fun createPayment(branchId: String, idempotencyKey: String, body: PaymentCreateRequest): Envelope<PaymentDto> = error("sin uso")
    override suspend fun recordPaymentResult(
        branchId: String,
        paymentId: String,
        ifMatch: String,
        idempotencyKey: String,
        body: PaymentResultRequest,
    ): Envelope<PaymentDto> = error("sin uso")
    override suspend fun createPrintJob(branchId: String, idempotencyKey: String, body: PrintJobRequest): Envelope<PrintJobDto> = error("sin uso")
    override suspend fun listReservations(branchId: String, from: String, to: String, page: Int, perPage: Int): PagedEnvelope<ReservationDto> =
        error("sin uso")
    override suspend fun waitingQueue(branchId: String): Envelope<List<QueueEntryDto>> = error("sin uso")
    override suspend fun reservationAvailability(branchId: String, startAt: String, endAt: String, partySize: Int): Envelope<AvailabilityDto> =
        error("sin uso")
    override suspend fun createReservation(branchId: String, idempotencyKey: String, body: ReservationCreateRequest): Envelope<ReservationDto> =
        error("sin uso")
    override suspend fun reservationAction(
        branchId: String,
        reservationId: String,
        action: String,
        ifMatch: String,
        idempotencyKey: String,
        body: JsonObject,
    ): Envelope<ReservationDto> = error("sin uso")
    override suspend fun searchCustomers(branchId: String, page: Int, perPage: Int, body: CustomerSearchRequest): PagedEnvelope<CustomerDto> =
        error("sin uso")
    override suspend fun createCustomer(branchId: String, idempotencyKey: String, body: CustomerCreateRequest): Envelope<CustomerDto> = error("sin uso")
    override suspend fun getCustomer(branchId: String, customerId: String): Envelope<CustomerDto> = error("sin uso")
    override suspend fun updateCustomer(branchId: String, customerId: String, ifMatch: String, body: CustomerUpdateRequest): Envelope<CustomerDto> =
        error("sin uso")
    override suspend fun getTableSession(branchId: String, sessionId: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun closeTableSession(branchId: String, sessionId: String, ifMatch: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun confirmCleaning(branchId: String, sessionId: String, tableId: String, body: CleaningConfirmationRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun changePassword(body: PasswordChangeRequest): Response<Unit> = error("sin uso")
    override suspend fun listMembers(branchId: String, page: Int, perPage: Int): PagedEnvelope<MemberDto> = error("sin uso")
    override suspend fun createMember(branchId: String, body: MemberCreateRequest): Envelope<MemberCreatedDto> = error("sin uso")
    override suspend fun changeMemberRole(branchId: String, userId: String, ifMatch: String, body: MemberRoleRequest): Envelope<MemberDto> =
        error("sin uso")
    override suspend fun revokeMember(branchId: String, userId: String, ifMatch: String, body: ReasonRequest): Envelope<MemberDto> = error("sin uso")
    override suspend fun passwordSetupLink(userId: String): Envelope<PasswordSetupLinkDto> = error("sin uso")
}
