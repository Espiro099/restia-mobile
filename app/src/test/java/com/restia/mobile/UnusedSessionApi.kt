package com.restia.mobile

import com.restia.mobile.core.network.ApprovalData
import com.restia.mobile.core.network.ApprovalRequest
import com.restia.mobile.core.network.CleaningConfirmationRequest
import com.restia.mobile.core.network.TableSessionUpdateRequest
import com.restia.mobile.core.network.TableSessionAddTablesRequest
import com.restia.mobile.core.network.TableReleaseRequest
import com.restia.mobile.core.network.AuthSessionDto
import com.restia.mobile.core.network.ReservationUpdateRequest
import com.restia.mobile.core.network.OrderUnitReassignRequest
import com.restia.mobile.core.network.ProductAvailabilityRequest
import com.restia.mobile.core.network.OrderItemUpdateRequest
import com.restia.mobile.core.network.NotificationDto
import com.restia.mobile.core.network.FoodSafetyRequest
import com.restia.mobile.core.network.IngredientDto
import com.restia.mobile.core.network.InventoryCountCreateRequest
import com.restia.mobile.core.network.InventoryCountDto
import com.restia.mobile.core.network.InventoryCountLinesRequest
import com.restia.mobile.core.network.InventoryMovementRequest
import com.restia.mobile.core.network.InventoryReceiptRequest
import com.restia.mobile.core.network.NotificationPageDto
import com.restia.mobile.core.network.NotificationsReadDto
import com.restia.mobile.core.network.CashRegisterDto
import com.restia.mobile.core.network.CashShiftDto
import com.restia.mobile.core.network.CashMovementCreateRequest
import com.restia.mobile.core.network.CashMovementDto
import com.restia.mobile.core.network.CashShiftCloseRequest
import com.restia.mobile.core.network.CashShiftOpenRequest
import com.restia.mobile.core.network.CashShiftSummaryDto
import com.restia.mobile.core.network.CheckAdjustmentRequest
import com.restia.mobile.core.network.CheckCreateRequest
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.CheckMoveUnitsRequest
import com.restia.mobile.core.network.AvailabilityDto
import com.restia.mobile.core.network.CustomerCreateRequest
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.core.network.CustomerSearchRequest
import com.restia.mobile.core.network.Envelope
import com.restia.mobile.core.network.PasswordChangeRequest
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.ReconcileRequest
import com.restia.mobile.core.network.RefundCreateRequest
import com.restia.mobile.core.network.RefundDto
import com.restia.mobile.core.network.RefundResultRequest
import com.restia.mobile.core.network.QueueEntryDto
import com.restia.mobile.core.network.ReservationCreateRequest
import com.restia.mobile.core.network.ReservationDto
import kotlinx.serialization.json.JsonObject
import com.restia.mobile.core.network.PaymentCreateRequest
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.PaymentResultRequest
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.core.network.PrintJobRequest
import com.restia.mobile.core.network.PrintJobResolveRequest
import com.restia.mobile.core.network.PrintJobRetryRequest
import com.restia.mobile.core.network.PrinterDto
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
    override suspend fun createCheck(branchId: String, idempotencyKey: String, body: CheckCreateRequest): Envelope<CheckDto> = error("sin uso")
    override suspend fun moveCheckUnits(branchId: String, checkId: String, ifMatch: String, body: CheckMoveUnitsRequest): Envelope<CheckDto> = error("sin uso")
    override suspend fun reopenCheck(branchId: String, checkId: String, ifMatch: String, body: ReasonRequest, approvalToken: String?): Envelope<CheckDto> =
        error("sin uso")
    override suspend fun cancelCheck(branchId: String, checkId: String, ifMatch: String, body: ReasonRequest): Envelope<CheckDto> = error("sin uso")
    override suspend fun createCheckAdjustment(
        branchId: String,
        checkId: String,
        ifMatch: String,
        body: CheckAdjustmentRequest,
        approvalToken: String?,
    ): Envelope<CheckDto> = error("sin uso")
    override suspend fun revertCheckAdjustment(
        branchId: String,
        checkId: String,
        adjustmentId: String,
        ifMatch: String,
        body: ReasonRequest,
        approvalToken: String?,
    ): Envelope<CheckDto> = error("sin uso")
    override suspend fun requestCheck(branchId: String, checkId: String, ifMatch: String): Envelope<CheckDto> = error("sin uso")
    override suspend fun listCashShifts(branchId: String, status: String, page: Int, perPage: Int): PagedEnvelope<CashShiftDto> = error("sin uso")
    override suspend fun listCashRegisters(branchId: String, page: Int, perPage: Int): PagedEnvelope<CashRegisterDto> = error("sin uso")
    override suspend fun openCashShift(branchId: String, idempotencyKey: String, body: CashShiftOpenRequest): Envelope<CashShiftDto> = error("sin uso")
    override suspend fun getCashShift(branchId: String, cashShiftId: String): Envelope<CashShiftDto> = error("sin uso")
    override suspend fun cashShiftSummary(branchId: String, cashShiftId: String): Envelope<CashShiftSummaryDto> = error("sin uso")
    override suspend fun listCashMovements(branchId: String, cashShiftId: String, page: Int, perPage: Int): PagedEnvelope<CashMovementDto> =
        error("sin uso")
    override suspend fun createCashMovement(
        branchId: String,
        cashShiftId: String,
        idempotencyKey: String,
        body: CashMovementCreateRequest,
    ): Envelope<CashMovementDto> = error("sin uso")
    override suspend fun startCashShiftClosing(branchId: String, cashShiftId: String, ifMatch: String): Envelope<CashShiftDto> = error("sin uso")
    override suspend fun cancelCashShiftClosing(branchId: String, cashShiftId: String, ifMatch: String, body: ReasonRequest): Envelope<CashShiftDto> =
        error("sin uso")
    override suspend fun closeCashShift(
        branchId: String,
        cashShiftId: String,
        ifMatch: String,
        idempotencyKey: String,
        body: CashShiftCloseRequest,
    ): Envelope<CashShiftDto> = error("sin uso")
    override suspend fun listShiftPayments(branchId: String, cashShiftId: String, page: Int, perPage: Int): PagedEnvelope<PaymentDto> = error("sin uso")
    override suspend fun reconcilePayment(branchId: String, paymentId: String, ifMatch: String, idempotencyKey: String, body: ReconcileRequest): Envelope<PaymentDto> =
        error("sin uso")
    override suspend fun createRefund(branchId: String, paymentId: String, idempotencyKey: String, body: RefundCreateRequest): Envelope<RefundDto> = error("sin uso")
    override suspend fun listRefunds(branchId: String, status: String, page: Int, perPage: Int): PagedEnvelope<RefundDto> = error("sin uso")
    override suspend fun recordRefundResult(branchId: String, refundId: String, ifMatch: String, idempotencyKey: String, body: RefundResultRequest): Envelope<RefundDto> =
        error("sin uso")
    override suspend fun reconcileRefund(branchId: String, refundId: String, ifMatch: String, idempotencyKey: String, body: ReconcileRequest): Envelope<RefundDto> =
        error("sin uso")
    override suspend fun listPayments(branchId: String, checkId: String, page: Int, perPage: Int): PagedEnvelope<PaymentDto> = error("sin uso")
    override suspend fun createPayment(branchId: String, idempotencyKey: String, body: PaymentCreateRequest): Envelope<PaymentDto> = error("sin uso")
    override suspend fun recordPaymentResult(
        branchId: String,
        paymentId: String,
        ifMatch: String,
        idempotencyKey: String,
        body: PaymentResultRequest,
    ): Envelope<PaymentDto> = error("sin uso")
    override suspend fun listPrintJobs(branchId: String, status: String?, page: Int, perPage: Int): PagedEnvelope<PrintJobDto> = error("sin uso")
    override suspend fun getPrintJob(branchId: String, printJobId: String): Envelope<PrintJobDto> = error("sin uso")
    override suspend fun listPrinters(branchId: String, page: Int, perPage: Int): PagedEnvelope<PrinterDto> = error("sin uso")
    override suspend fun retryPrintJob(branchId: String, printJobId: String, ifMatch: String, body: PrintJobRetryRequest): Envelope<PrintJobDto> = error("sin uso")
    override suspend fun resolvePrintJob(branchId: String, printJobId: String, ifMatch: String, body: PrintJobResolveRequest): Envelope<PrintJobDto> =
        error("sin uso")
    override suspend fun cancelPrintJob(branchId: String, printJobId: String, ifMatch: String, body: ReasonRequest): Envelope<PrintJobDto> = error("sin uso")
    override suspend fun reprintPrintJob(branchId: String, printJobId: String, idempotencyKey: String, body: ReasonRequest): Envelope<PrintJobDto> =
        error("sin uso")
    override suspend fun createPrintJob(branchId: String, idempotencyKey: String, body: PrintJobRequest): Envelope<PrintJobDto> = error("sin uso")
    override suspend fun listReservations(branchId: String, from: String, to: String, page: Int, perPage: Int): PagedEnvelope<ReservationDto> =
        error("sin uso")
    override suspend fun waitingQueue(branchId: String): Envelope<List<QueueEntryDto>> = error("sin uso")
    override suspend fun reservationAvailability(branchId: String, startAt: String, endAt: String, partySize: Int): Envelope<AvailabilityDto> =
        error("sin uso")
    override suspend fun createReservation(branchId: String, idempotencyKey: String, body: ReservationCreateRequest): Envelope<ReservationDto> =
        error("sin uso")
    override suspend fun updateReservation(branchId: String, reservationId: String, ifMatch: String, idempotencyKey: String, body: ReservationUpdateRequest): Envelope<ReservationDto> =
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
    override suspend fun getTableSession(branchId: String, sessionId: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun listIngredients(branchId: String, status: String, page: Int, perPage: Int): PagedEnvelope<IngredientDto> = error("sin uso")
    override suspend fun createInventoryMovement(branchId: String, idempotencyKey: String, body: InventoryMovementRequest): ResponseBody = error("sin uso")
    override suspend fun createInventoryReceipt(branchId: String, idempotencyKey: String, body: InventoryReceiptRequest): ResponseBody = error("sin uso")
    override suspend fun listInventoryCounts(branchId: String, status: String, perPage: Int): PagedEnvelope<InventoryCountDto> = error("sin uso")
    override suspend fun createInventoryCount(branchId: String, idempotencyKey: String, body: InventoryCountCreateRequest): Envelope<InventoryCountDto> = error("sin uso")
    override suspend fun replaceInventoryCountLines(branchId: String, countId: String, ifMatch: String, body: InventoryCountLinesRequest): Envelope<InventoryCountDto> = error("sin uso")
    override suspend fun setFoodSafety(branchId: String, ingredientId: String, body: FoodSafetyRequest): ResponseBody = error("sin uso")
    override suspend fun listNotifications(branchId: String, perPage: Int): NotificationPageDto = error("sin uso")
    override suspend fun readNotification(branchId: String, notificationId: String): Envelope<NotificationDto> = error("sin uso")
    override suspend fun readAllNotifications(branchId: String): Envelope<NotificationsReadDto> = error("sin uso")
    override suspend fun updateTableSession(branchId: String, sessionId: String, ifMatch: String, body: TableSessionUpdateRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun updateOrderItemNotes(branchId: String, orderId: String, itemId: String, ifMatch: String, body: OrderItemUpdateRequest): Envelope<OrderDto> =
        error("sin uso")
    override suspend fun setProductAvailability(branchId: String, productId: String, body: ProductAvailabilityRequest): ResponseBody = error("sin uso")
    override suspend fun reassignOrderUnitStation(branchId: String, unitId: String, ifMatch: String, body: OrderUnitReassignRequest): ResponseBody = error("sin uso")
    override suspend fun addTablesToSession(branchId: String, sessionId: String, ifMatch: String, body: TableSessionAddTablesRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun releaseTableFromSession(branchId: String, sessionId: String, tableId: String, ifMatch: String, body: TableReleaseRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun cancelTableSession(branchId: String, sessionId: String, ifMatch: String, body: ReasonRequest): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun closeTableSession(branchId: String, sessionId: String, ifMatch: String): Envelope<TableSessionDto> = error("sin uso")
    override suspend fun confirmCleaning(branchId: String, sessionId: String, tableId: String, body: CleaningConfirmationRequest): Envelope<TableSessionDto> =
        error("sin uso")
    override suspend fun changePassword(body: PasswordChangeRequest): Response<Unit> = error("sin uso")
    override suspend fun listOwnSessions(status: String, page: Int, perPage: Int): PagedEnvelope<AuthSessionDto> = error("sin uso")
    override suspend fun revokeOwnSession(sessionId: String): Response<Unit> = error("sin uso")
}
