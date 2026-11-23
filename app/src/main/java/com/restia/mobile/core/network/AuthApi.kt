package com.restia.mobile.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Operaciones sin Bearer: alta, login y renovación. */
interface PublicAuthApi {
    @POST("auth/mobile/enroll")
    suspend fun enroll(@Body body: DeviceEnrollRequest): Envelope<DeviceEnrollData>

    @POST("auth/mobile/login")
    suspend fun login(@Body body: MobileLoginRequest): Envelope<TokenData>

    @POST("auth/mobile/refresh")
    fun refresh(@Body body: MobileRefreshRequest): Call<Envelope<TokenData>>

    /** Siempre 204, exista o no el correo. */
    @POST("auth/password/forgot")
    suspend fun forgotPassword(@Body body: PasswordForgotRequest): Response<Unit>

    @POST("auth/logout")
    suspend fun logoutWith(@Header("Authorization") bearer: String): Response<Unit>
}

/** Operaciones con Bearer; pasan por el interceptor y el authenticator. */
interface SessionApi {
    @GET("auth/me")
    suspend fun me(): Envelope<MeData>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    /** Sesiones propias (Android y web); `current` marca la que hace la petición. */
    @GET("auth/sessions")
    suspend fun listOwnSessions(
        @Query("status") status: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<AuthSessionDto>

    /** 204; una sesión ajena responde 404 y una ya cerrada no cambia. */
    @POST("auth/sessions/{sessionId}/revoke")
    suspend fun revokeOwnSession(@Path("sessionId") sessionId: String): Response<Unit>

    /** Cierra las demás sesiones del usuario; esta sigue abierta. */
    @POST("auth/password/change")
    suspend fun changePassword(@Body body: PasswordChangeRequest): Response<Unit>

    @GET("branches/{branchId}/tables")
    suspend fun listTables(
        @Path("branchId") branchId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<TableDto>

    @GET("branches/{branchId}/menu")
    suspend fun getMenu(
        @Path("branchId") branchId: String,
        @Header("If-None-Match") etag: String?,
    ): Response<Envelope<MenuDto>>

    @GET("branches/{branchId}/orders")
    suspend fun listOrders(
        @Path("branchId") branchId: String,
        @Query("sessionId") sessionId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
        @Header("If-None-Match") etag: String?,
    ): Response<PagedEnvelope<OrderDto>>

    @GET("branches/{branchId}/stations")
    suspend fun listStations(
        @Path("branchId") branchId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<StationDto>

    /** Sin status, el backend devuelve PENDING, PREPARING y READY, de la más antigua a la más reciente. */
    @GET("branches/{branchId}/order-units")
    suspend fun listQueue(
        @Path("branchId") branchId: String,
        @Query("stationId") stationId: String?,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
        @Header("If-None-Match") etag: String?,
    ): Response<PagedEnvelope<KitchenUnitDto>>

    /** AVAILABLE o SOLD_OUT; sin If-Match: marcarlo dos veces deja lo mismo. */
    @POST("branches/{branchId}/products/{productId}/availability")
    suspend fun setProductAvailability(
        @Path("branchId") branchId: String,
        @Path("productId") productId: String,
        @Body body: ProductAvailabilityRequest,
    ): ResponseBody

    /** Mueve una unidad PENDING a otra estación activa, con motivo. */
    @POST("branches/{branchId}/order-units/{unitId}/station")
    suspend fun reassignOrderUnitStation(
        @Path("branchId") branchId: String,
        @Path("unitId") unitId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: OrderUnitReassignRequest,
    ): ResponseBody

    @POST("branches/{branchId}/order-units/transitions")
    suspend fun transitionUnits(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: UnitTransitionRequest,
        // Solo en VOID sin permiso directo; Retrofit no envía el header si es null.
        @Header("X-Approval-Token") approvalToken: String? = null,
    ): ResponseBody

    /** Credenciales incorrectas responden 403, nunca 401, para no tocar la sesión del mesero. */
    @POST("branches/{branchId}/approvals")
    suspend fun createApproval(
        @Path("branchId") branchId: String,
        @Body body: ApprovalRequest,
    ): Envelope<ApprovalData>

    @POST("branches/{branchId}/orders")
    suspend fun createOrder(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: OrderCreateRequest,
    ): Envelope<OrderDto>

    @POST("branches/{branchId}/table-sessions")
    suspend fun openTableSession(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: TableSessionOpenRequest,
    ): Envelope<TableSessionDto>

    @GET("branches/{branchId}/checks")
    suspend fun listChecks(
        @Path("branchId") branchId: String,
        @Query("sessionId") sessionId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<CheckDto>

    @POST("branches/{branchId}/checks")
    suspend fun createCheck(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CheckCreateRequest,
    ): Envelope<CheckDto>

    /** Devuelve la cuenta de origen. */
    @POST("branches/{branchId}/checks/{checkId}/units/move")
    suspend fun moveCheckUnits(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: CheckMoveUnitsRequest,
    ): Envelope<CheckDto>

    /** REQUESTED → OPEN; sin checks.reopen, con el token CHECK_REOPEN de un gerente (C-9). */
    @POST("branches/{branchId}/checks/{checkId}/reopen")
    suspend fun reopenCheck(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
        @Header("X-Approval-Token") approvalToken: String? = null,
    ): Envelope<CheckDto>

    /** Solo sin unidades válidas ni pagos. */
    @POST("branches/{branchId}/checks/{checkId}/cancel")
    suspend fun cancelCheck(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
    ): Envelope<CheckDto>

    /** Sin checks.adjust, con el token CHECK_ADJUSTMENT ligado a la unidad. */
    @POST("branches/{branchId}/checks/{checkId}/adjustments")
    suspend fun createCheckAdjustment(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: CheckAdjustmentRequest,
        @Header("X-Approval-Token") approvalToken: String? = null,
    ): Envelope<CheckDto>

    /** Sin checks.adjust, con el token CHECK_ADJUSTMENT ligado a la cortesía. */
    @POST("branches/{branchId}/checks/{checkId}/adjustments/{adjustmentId}/revert")
    suspend fun revertCheckAdjustment(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Path("adjustmentId") adjustmentId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
        @Header("X-Approval-Token") approvalToken: String? = null,
    ): Envelope<CheckDto>

    /** Marca la cuenta como solicitada; la mesa pasa a CUENTA_SOLICITADA. */
    @POST("branches/{branchId}/checks/{checkId}/request")
    suspend fun requestCheck(
        @Path("branchId") branchId: String,
        @Path("checkId") checkId: String,
        @Header("If-Match") ifMatch: String,
    ): Envelope<CheckDto>

    /** Con solo cash.read_open, el backend devuelve los turnos abiertos sin montos. */
    @GET("branches/{branchId}/cash-shifts")
    suspend fun listCashShifts(
        @Path("branchId") branchId: String,
        @Query("status") status: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<CashShiftDto>

    @GET("branches/{branchId}/cash-registers")
    suspend fun listCashRegisters(
        @Path("branchId") branchId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<CashRegisterDto>

    @POST("branches/{branchId}/cash-shifts")
    suspend fun openCashShift(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CashShiftOpenRequest,
    ): Envelope<CashShiftDto>

    @GET("branches/{branchId}/cash-shifts/{cashShiftId}")
    suspend fun getCashShift(@Path("branchId") branchId: String, @Path("cashShiftId") cashShiftId: String): Envelope<CashShiftDto>

    @GET("branches/{branchId}/cash-shifts/{cashShiftId}/summary")
    suspend fun cashShiftSummary(@Path("branchId") branchId: String, @Path("cashShiftId") cashShiftId: String): Envelope<CashShiftSummaryDto>

    @GET("branches/{branchId}/cash-shifts/{cashShiftId}/movements")
    suspend fun listCashMovements(
        @Path("branchId") branchId: String,
        @Path("cashShiftId") cashShiftId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<CashMovementDto>

    @POST("branches/{branchId}/cash-shifts/{cashShiftId}/movements")
    suspend fun createCashMovement(
        @Path("branchId") branchId: String,
        @Path("cashShiftId") cashShiftId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CashMovementCreateRequest,
    ): Envelope<CashMovementDto>

    /** Frena cobros, depósitos y retiros nuevos; sin cuerpo. */
    @POST("branches/{branchId}/cash-shifts/{cashShiftId}/start-closing")
    suspend fun startCashShiftClosing(
        @Path("branchId") branchId: String,
        @Path("cashShiftId") cashShiftId: String,
        @Header("If-Match") ifMatch: String,
    ): Envelope<CashShiftDto>

    @POST("branches/{branchId}/cash-shifts/{cashShiftId}/cancel-closing")
    suspend fun cancelCashShiftClosing(
        @Path("branchId") branchId: String,
        @Path("cashShiftId") cashShiftId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
    ): Envelope<CashShiftDto>

    @POST("branches/{branchId}/cash-shifts/{cashShiftId}/close")
    suspend fun closeCashShift(
        @Path("branchId") branchId: String,
        @Path("cashShiftId") cashShiftId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CashShiftCloseRequest,
    ): Envelope<CashShiftDto>

    @GET("branches/{branchId}/payments")
    suspend fun listShiftPayments(
        @Path("branchId") branchId: String,
        @Query("cashShiftId") cashShiftId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<PaymentDto>

    @POST("branches/{branchId}/payments/{paymentId}/reconcile")
    suspend fun reconcilePayment(
        @Path("branchId") branchId: String,
        @Path("paymentId") paymentId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReconcileRequest,
    ): Envelope<PaymentDto>

    @POST("branches/{branchId}/payments/{paymentId}/refunds")
    suspend fun createRefund(
        @Path("branchId") branchId: String,
        @Path("paymentId") paymentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RefundCreateRequest,
    ): Envelope<RefundDto>

    /** Sin filtro por turno en el contrato: se filtra por estado y la app separa los del turno. */
    @GET("branches/{branchId}/refunds")
    suspend fun listRefunds(
        @Path("branchId") branchId: String,
        @Query("status") status: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<RefundDto>

    @POST("branches/{branchId}/refunds/{refundId}/result")
    suspend fun recordRefundResult(
        @Path("branchId") branchId: String,
        @Path("refundId") refundId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RefundResultRequest,
    ): Envelope<RefundDto>

    @POST("branches/{branchId}/refunds/{refundId}/reconcile")
    suspend fun reconcileRefund(
        @Path("branchId") branchId: String,
        @Path("refundId") refundId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReconcileRequest,
    ): Envelope<RefundDto>

    @GET("branches/{branchId}/payments")
    suspend fun listPayments(
        @Path("branchId") branchId: String,
        @Query("checkId") checkId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<PaymentDto>

    @POST("branches/{branchId}/payments")
    suspend fun createPayment(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PaymentCreateRequest,
    ): Envelope<PaymentDto>

    @POST("branches/{branchId}/payments/{paymentId}/result")
    suspend fun recordPaymentResult(
        @Path("branchId") branchId: String,
        @Path("paymentId") paymentId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PaymentResultRequest,
    ): Envelope<PaymentDto>

    /** Del más reciente al más antiguo. */
    @GET("branches/{branchId}/print-jobs")
    suspend fun listPrintJobs(
        @Path("branchId") branchId: String,
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<PrintJobDto>

    @GET("branches/{branchId}/printers")
    suspend fun listPrinters(
        @Path("branchId") branchId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<PrinterDto>

    /** Solo FAILED; un UNKNOWN primero se resuelve (PRINT_JOB_UNKNOWN). */
    @POST("branches/{branchId}/print-jobs/{printJobId}/retry")
    suspend fun retryPrintJob(
        @Path("branchId") branchId: String,
        @Path("printJobId") printJobId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: PrintJobRetryRequest,
    ): Envelope<PrintJobDto>

    @POST("branches/{branchId}/print-jobs/{printJobId}/resolve")
    suspend fun resolvePrintJob(
        @Path("branchId") branchId: String,
        @Path("printJobId") printJobId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: PrintJobResolveRequest,
    ): Envelope<PrintJobDto>

    @POST("branches/{branchId}/print-jobs/{printJobId}/cancel")
    suspend fun cancelPrintJob(
        @Path("branchId") branchId: String,
        @Path("printJobId") printJobId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
    ): Envelope<PrintJobDto>

    /** Crea un trabajo nuevo con el contenido original, marcado como REIMPRESIÓN. */
    @POST("branches/{branchId}/print-jobs/{printJobId}/reprint")
    suspend fun reprintPrintJob(
        @Path("branchId") branchId: String,
        @Path("printJobId") printJobId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReasonRequest,
    ): Envelope<PrintJobDto>

    @POST("branches/{branchId}/print-jobs")
    suspend fun createPrintJob(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: PrintJobRequest,
    ): Envelope<PrintJobDto>

    /** Reservas que se traslapan con el intervalo: terminan después de `from` y empiezan antes de `to`. */
    @GET("branches/{branchId}/reservations")
    suspend fun listReservations(
        @Path("branchId") branchId: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<ReservationDto>

    @GET("branches/{branchId}/reservations/waiting-queue")
    suspend fun waitingQueue(@Path("branchId") branchId: String): Envelope<List<QueueEntryDto>>

    @GET("branches/{branchId}/reservations/availability")
    suspend fun reservationAvailability(
        @Path("branchId") branchId: String,
        @Query("startAt") startAt: String,
        @Query("endAt") endAt: String,
        @Query("partySize") partySize: Int,
    ): Envelope<AvailabilityDto>

    @POST("branches/{branchId}/reservations")
    suspend fun createReservation(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReservationCreateRequest,
    ): Envelope<ReservationDto>

    /** Cambia horario, personas o notas de una reserva PENDING o CONFIRMED; replanea las mesas. */
    @PATCH("branches/{branchId}/reservations/{reservationId}")
    suspend fun updateReservation(
        @Path("branchId") branchId: String,
        @Path("reservationId") reservationId: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: ReservationUpdateRequest,
    ): Envelope<ReservationDto>

    /** confirm, arrive, check-in, cancel o no-show: todas con If-Match e Idempotency-Key (R-12). */
    @POST("branches/{branchId}/reservations/{reservationId}/{action}")
    suspend fun reservationAction(
        @Path("branchId") branchId: String,
        @Path("reservationId") reservationId: String,
        @Path("action") action: String,
        @Header("If-Match") ifMatch: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: JsonObject,
    ): Envelope<ReservationDto>

    @POST("branches/{branchId}/customers/search")
    suspend fun searchCustomers(
        @Path("branchId") branchId: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
        @Body body: CustomerSearchRequest,
    ): PagedEnvelope<CustomerDto>

    @POST("branches/{branchId}/customers")
    suspend fun createCustomer(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CustomerCreateRequest,
    ): Envelope<CustomerDto>

    @GET("branches/{branchId}/table-sessions/{sessionId}")
    suspend fun getTableSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
    ): Envelope<TableSessionDto>

    // Inventario de cocina (Decisiones_Inventario): insumos, merma, recepción, conteo e inocuidad.

    @GET("branches/{branchId}/ingredients")
    suspend fun listIngredients(
        @Path("branchId") branchId: String,
        @Query("status") status: String,
        @Query("page") page: Int,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<IngredientDto>

    /** WASTE (inventory.record_waste): cantidad positiva y motivo. Idempotente por clave. */
    @POST("branches/{branchId}/inventory-movements")
    suspend fun createInventoryMovement(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: InventoryMovementRequest,
    ): ResponseBody

    /** Entrada de mercancía (inventory.receive), con proveedor y costo unitario. */
    @POST("branches/{branchId}/inventory-receipts")
    suspend fun createInventoryReceipt(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: InventoryReceiptRequest,
    ): ResponseBody

    @GET("branches/{branchId}/inventory-counts")
    suspend fun listInventoryCounts(
        @Path("branchId") branchId: String,
        @Query("status") status: String,
        @Query("perPage") perPage: Int,
    ): PagedEnvelope<InventoryCountDto>

    /** Un solo conteo abierto por sucursal: si ya hay uno, 409 INVENTORY_COUNT_ALREADY_OPEN. */
    @POST("branches/{branchId}/inventory-counts")
    suspend fun createInventoryCount(
        @Path("branchId") branchId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: InventoryCountCreateRequest,
    ): Envelope<InventoryCountDto>

    /** Reemplaza todas las líneas del conteo; lo esperado lo calcula el servidor. */
    @PUT("branches/{branchId}/inventory-counts/{countId}/lines")
    suspend fun replaceInventoryCountLines(
        @Path("branchId") branchId: String,
        @Path("countId") countId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: InventoryCountLinesRequest,
    ): Envelope<InventoryCountDto>

    /** Cocina puede bloquear un insumo por inocuidad; desbloquear es del gerente. */
    @POST("branches/{branchId}/ingredients/{ingredientId}/food-safety")
    suspend fun setFoodSafety(
        @Path("branchId") branchId: String,
        @Path("ingredientId") ingredientId: String,
        @Body body: FoodSafetyRequest,
    ): ResponseBody

    /** Avisos propios en la sucursal, los más recientes primero; `meta.unreadCount` da los no leídos. */
    @GET("branches/{branchId}/notifications")
    suspend fun listNotifications(
        @Path("branchId") branchId: String,
        @Query("perPage") perPage: Int,
    ): NotificationPageDto

    /** Sin If-Match: marcarlo dos veces deja lo mismo. */
    @POST("branches/{branchId}/notifications/{notificationId}/read")
    suspend fun readNotification(
        @Path("branchId") branchId: String,
        @Path("notificationId") notificationId: String,
    ): Envelope<NotificationDto>

    @POST("branches/{branchId}/notifications/read-all")
    suspend fun readAllNotifications(@Path("branchId") branchId: String): Envelope<NotificationsReadDto>

    /** Con If-Match: repetirlo con la versión vieja responde 409, no se aplica dos veces. */
    @PATCH("branches/{branchId}/table-sessions/{sessionId}")
    suspend fun updateTableSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: TableSessionUpdateRequest,
    ): Envelope<TableSessionDto>

    /** Nota de una partida mientras todas sus unidades sigan PENDING. */
    @PATCH("branches/{branchId}/orders/{orderId}/items/{itemId}")
    suspend fun updateOrderItemNotes(
        @Path("branchId") branchId: String,
        @Path("orderId") orderId: String,
        @Path("itemId") itemId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: OrderItemUpdateRequest,
    ): Envelope<OrderDto>

    /** Junta mesas libres a la sesión (T-2). */
    @POST("branches/{branchId}/table-sessions/{sessionId}/tables")
    suspend fun addTablesToSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: TableSessionAddTablesRequest,
    ): Envelope<TableSessionDto>

    /** Separa una mesa de la sesión; la última no se puede liberar (SESSION_LAST_TABLE). */
    @POST("branches/{branchId}/table-sessions/{sessionId}/tables/{tableId}/release")
    suspend fun releaseTableFromSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Path("tableId") tableId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: TableReleaseRequest,
    ): Envelope<TableSessionDto>

    /** Anula una apertura sin actividad (sin platillos ni pagos), con motivo. */
    @POST("branches/{branchId}/table-sessions/{sessionId}/cancel")
    suspend fun cancelTableSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: ReasonRequest,
    ): Envelope<TableSessionDto>

    /** T-4: sin cuerpo; las mesas quedan por limpiar y la sesión en PENDING_CLEANING. */
    @POST("branches/{branchId}/table-sessions/{sessionId}/close")
    suspend fun closeTableSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Header("If-Match") ifMatch: String,
    ): Envelope<TableSessionDto>

    /** Sin If-Match: repetir una confirmación ya hecha responde igual. */
    @POST("branches/{branchId}/table-sessions/{sessionId}/tables/{tableId}/cleaning-confirmation")
    suspend fun confirmCleaning(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
        @Path("tableId") tableId: String,
        @Body body: CleaningConfirmationRequest,
    ): Envelope<TableSessionDto>
}
