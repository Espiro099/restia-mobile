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

    @POST("auth/logout")
    suspend fun logoutWith(@Header("Authorization") bearer: String): Response<Unit>
}

/** Operaciones con Bearer; pasan por el interceptor y el authenticator. */
interface SessionApi {
    @GET("auth/me")
    suspend fun me(): Envelope<MeData>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

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

    @GET("branches/{branchId}/customers/{customerId}")
    suspend fun getCustomer(@Path("branchId") branchId: String, @Path("customerId") customerId: String): Envelope<CustomerDto>

    @PATCH("branches/{branchId}/customers/{customerId}")
    suspend fun updateCustomer(
        @Path("branchId") branchId: String,
        @Path("customerId") customerId: String,
        @Header("If-Match") ifMatch: String,
        @Body body: CustomerUpdateRequest,
    ): Envelope<CustomerDto>

    @GET("branches/{branchId}/table-sessions/{sessionId}")
    suspend fun getTableSession(
        @Path("branchId") branchId: String,
        @Path("sessionId") sessionId: String,
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
