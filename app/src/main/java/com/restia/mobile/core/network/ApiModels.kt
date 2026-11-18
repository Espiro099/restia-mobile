package com.restia.mobile.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class Envelope<T>(val data: T)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
    val requestId: String? = null,
)

@Serializable
data class DeviceEnrollRequest(val code: String, val deviceUuid: String, val displayName: String)

@Serializable
data class DeviceDto(
    val id: String,
    val branchId: String,
    val status: String,
    val displayName: String,
    val type: String? = null,
    val enrolledAt: String? = null,
    val lastSeenAt: String? = null,
    val version: Int = 0,
)

/** Código de un solo uso para dar de alta un teléfono; se escribe en la pantalla de alta. */
@Serializable
data class EnrollmentCodeDto(val code: String, val expiresAt: String)

@Serializable
data class DeviceEnrollData(val device: DeviceDto, val deviceCredential: String)

@Serializable
data class MobileLoginRequest(
    val email: String,
    val password: String,
    val deviceUuid: String,
    val deviceCredential: String,
)

@Serializable
data class MobileRefreshRequest(val deviceUuid: String, val refreshToken: String)

@Serializable
data class TokenData(val accessToken: String, val accessTokenExpiresIn: Long, val refreshToken: String)

@Serializable
data class UserDto(val id: String, val name: String, val email: String)

@Serializable
data class BranchDto(val id: String, val name: String)

@Serializable
data class MembershipDto(val branch: BranchDto, val role: String, val permissions: List<String>)

@Serializable
data class PaginationDto(val page: Int, val lastPage: Int)

@Serializable
data class MetaDto(val pagination: PaginationDto? = null)

@Serializable
data class PagedEnvelope<T>(val data: List<T>, val meta: MetaDto = MetaDto())

@Serializable
data class TableDto(
    val id: String,
    val number: Int,
    val capacity: Int,
    val zone: String? = null,
    val status: String,
    val operationalState: String? = null,
    val currentSessionId: String? = null,
)

@Serializable
data class TableSessionOpenRequest(
    val tableIds: List<String>,
    val guestCount: Int,
    val acknowledgeReservation: Boolean? = null,
)

@Serializable
data class SessionTableDto(
    val tableId: String,
    val number: Int,
    /** SEATED o CLEANING. */
    val phase: String,
    /** Versión que pide la confirmación de limpieza (T-3). */
    val cleaningMembershipVersion: Int? = null,
)

@Serializable
data class TableSessionDto(
    val id: String,
    val status: String,
    val guestCount: Int,
    val version: Int,
    val tables: List<SessionTableDto> = emptyList(),
)

/** Aviso de la campana: orden lista, cuenta solicitada o resultado de un pago con terminal. */
@Serializable
data class NotificationDto(
    val id: String,
    /** ORDER_READY, CHECK_REQUESTED, PAYMENT_CONFIRMED, PAYMENT_FAILED o PAYMENT_UNKNOWN. */
    val type: String,
    val title: String,
    val body: String,
    val tableNumber: Int = 0,
    val sessionId: String? = null,
    val checkId: String? = null,
    val count: Int = 1,
    val readAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class NotificationMetaDto(val unreadCount: Int = 0, val pagination: PaginationDto? = null)

@Serializable
data class NotificationPageDto(val data: List<NotificationDto>, val meta: NotificationMetaDto = NotificationMetaDto())

@Serializable
data class NotificationsReadDto(val updated: Int, val unreadCount: Int)

/** Una sesión propia: ANDROID o WEB, ACTIVE, REVOKED o EXPIRED. */
@Serializable
data class AuthSessionDto(
    val id: String,
    val clientType: String,
    val status: String,
    val current: Boolean = false,
    val deviceId: String? = null,
    val createdAt: String,
    val lastActivityAt: String,
    val absoluteExpiresAt: String,
    val revokedAt: String? = null,
)

/** Solo lo que cambia; con explicitNulls = false lo null no se envía. Notas vacías las borran. */
@Serializable
data class ReservationUpdateRequest(
    val startAt: String? = null,
    val endAt: String? = null,
    val partySize: Int? = null,
    val notes: String? = null,
)

@Serializable
data class ProductAvailabilityRequest(val availability: String)

@Serializable
data class OrderUnitReassignRequest(val stationId: String, val reason: String)

@Serializable
data class TableSessionAddTablesRequest(val tableIds: List<String>)

@Serializable
data class TableReleaseRequest(val requiresCleaning: Boolean)

/** PATCH de la sesión: por ahora solo cambia cuántos comensales hay. */
@Serializable
data class TableSessionUpdateRequest(val guestCount: Int)

@Serializable
data class CleaningConfirmationRequest(val membershipVersion: Int)

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class MenuProductDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String,
    val displayOrder: Int,
    val price: MoneyDto,
    val availability: String,
    val modifierIds: List<String> = emptyList(),
    /** Foto del platillo; solo se muestra si es HTTPS (ImageClient.isAllowed). */
    val imageUrl: String? = null,
)

@Serializable
data class MenuOptionDto(val id: String, val name: String, val priceDelta: MoneyDto)

@Serializable
data class MenuModifierDto(
    val id: String,
    val name: String,
    val minSelections: Int,
    val maxSelections: Int,
    val options: List<MenuOptionDto>,
)

@Serializable
data class MenuDto(val menuVersion: Int, val products: List<MenuProductDto>, val modifiers: List<MenuModifierDto>)

@Serializable
data class OrderModifierOption(val modifierId: String, val optionId: String)

@Serializable
data class OrderItemRequest(
    val productId: String,
    val quantity: Int,
    val modifierOptions: List<OrderModifierOption>? = null,
    val notes: String? = null,
)

@Serializable
data class OrderCreateRequest(
    val sessionId: String,
    val clientOrderUuid: String,
    val capturedAt: String? = null,
    val items: List<OrderItemRequest>,
)

@Serializable
data class OrderDto(
    val id: String,
    val clientOrderUuid: String,
    val status: String,
    val total: MoneyDto,
    val createdAt: String? = null,
    val items: List<OrderItemDto> = emptyList(),
)

@Serializable
data class OrderItemDto(
    val id: String,
    val name: String,
    val quantity: Int,
    val modifiers: List<OrderModifierDto> = emptyList(),
    val notes: String? = null,
    val units: List<OrderUnitDto> = emptyList(),
    /** Para el If-Match al editar las notas. */
    val version: Int = 0,
)

/**
 * La API exige `notes` presente, también para borrarla. Con explicitNulls = false un String? null no
 * se enviaría, así que va como JsonElement: JsonNull se escribe como `null`.
 */
@Serializable
data class OrderItemUpdateRequest(val notes: JsonElement) {
    companion object {
        fun of(notes: String?): OrderItemUpdateRequest = OrderItemUpdateRequest(notes?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

@Serializable
data class OrderModifierDto(val name: String)

@Serializable
data class OrderUnitDto(val id: String, val status: String, val version: Int)

@Serializable
data class UnitVersion(val id: String, val version: Int)

@Serializable
data class UnitTransitionRequest(
    val action: String,
    val units: List<UnitVersion>,
    /** Obligatorio en CANCEL y VOID; con explicitNulls = false no se envía si es null. */
    val reason: String? = null,
)

@Serializable
data class StationDto(val id: String, val code: String, val name: String, val status: String)

/** Unidad en la cola de cocina, con lo que necesita la estación para prepararla. */
@Serializable
data class QueueUnitDto(
    val id: String,
    val orderId: String,
    val orderItemId: String,
    val unitNumber: Int,
    val status: String,
    val stationId: String,
    val createdAt: String,
    val version: Int,
)

@Serializable
data class KitchenUnitDto(
    val unit: QueueUnitDto,
    val productName: String,
    val modifiers: List<OrderModifierDto> = emptyList(),
    val notes: String? = null,
    val tableNumbers: List<Int> = emptyList(),
)

@Serializable
data class CheckAdjustmentDto(val id: String, val type: String, val amount: MoneyDto, val reason: String)

@Serializable
data class CheckUnitDto(
    val unitId: String,
    val orderId: String,
    val productName: String,
    val status: String,
    val price: MoneyDto,
    val tax: MoneyDto,
    val adjustment: CheckAdjustmentDto? = null,
)

@Serializable
data class CheckDto(
    val id: String,
    val sessionId: String,
    val number: Int,
    val label: String? = null,
    val status: String,
    val units: List<CheckUnitDto> = emptyList(),
    val subtotal: MoneyDto,
    val taxTotal: MoneyDto,
    val adjustmentTotal: MoneyDto,
    val total: MoneyDto,
    val paidTotal: MoneyDto,
    val pendingTotal: MoneyDto,
    /** Lo devuelto; el backend no lo resta del saldo, así que una cuenta reembolsada puede quedar en negativo. */
    val refundedTotal: MoneyDto? = null,
    val tipTotal: MoneyDto,
    val balance: MoneyDto,
    val version: Int,
)

@Serializable
data class CashShiftDto(
    val id: String,
    val cashRegisterId: String,
    val status: String,
    val openedAt: String? = null,
    val businessDate: String? = null,
    // Los montos y motivos solo llegan con cash.read; con cash.read_open, el turno viene sin ellos.
    val openingAmount: MoneyDto? = null,
    val openingDifference: MoneyDto? = null,
    val closingStartedAt: String? = null,
    val closedAt: String? = null,
    val expectedCash: MoneyDto? = null,
    val countedCash: MoneyDto? = null,
    val difference: MoneyDto? = null,
    val cashLeftInDrawer: MoneyDto? = null,
    val reviewRequired: Boolean = false,
    val version: Int = 1,
)

@Serializable
data class CashRegisterDto(val id: String, val code: String, val name: String, val status: String, val version: Int = 1)

@Serializable
data class CardDto(val brand: String? = null, val last4: String? = null, val authCode: String? = null)

@Serializable
data class PaymentDto(
    val id: String,
    val checkId: String,
    val cashShiftId: String,
    val method: String,
    val status: String,
    val amount: MoneyDto,
    val tip: MoneyDto,
    val amountTendered: MoneyDto? = null,
    val change: MoneyDto? = null,
    val card: CardDto? = null,
    val reference: String? = null,
    /** Reembolso activo del pago, si lo tiene (uno por pago). */
    val refundId: String? = null,
    val createdAt: String,
    val version: Int,
)

/** Efectivo: lleva lo entregado y queda CONFIRMED al crear. Tarjeta y transferencia: PENDING hasta el resultado (P-2). */
@Serializable
data class PaymentCreateRequest(
    val checkId: String,
    val cashShiftId: String,
    val method: String,
    val amount: MoneyDto,
    val tip: MoneyDto? = null,
    val amountTendered: MoneyDto? = null,
)

/** Nunca el número completo de la tarjeta (P-10). */
@Serializable
data class PaymentResultRequest(
    val outcome: String,
    val reference: String? = null,
    val card: CardDto? = null,
)

@Serializable
data class PrintJobRequest(val type: String, val sourceId: String)

@Serializable
data class PrintJobDto(
    val id: String,
    val type: String,
    val status: String,
    val printerId: String? = null,
    val sourceId: String? = null,
    /** Trabajo original de una reimpresión. */
    val originalJobId: String? = null,
    val reason: String? = null,
    val attempts: Int = 0,
    val lastError: PrintErrorDto? = null,
    val createdAt: String? = null,
    val printedAt: String? = null,
    val version: Int = 1,
)

@Serializable
data class PrintErrorDto(val code: String, val message: String)

/** Copia del cliente en la reserva (R-11). Sin teléfono una vez borrados los datos personales. */
@Serializable
data class ReservationCustomerDto(val customerId: String? = null, val name: String, val phone: String? = null)

@Serializable
data class ReservationDto(
    val id: String,
    val status: String,
    val startAt: String,
    val endAt: String,
    val partySize: Int,
    val customer: ReservationCustomerDto,
    val notes: String? = null,
    val confirmationCode: String,
    val plannedTableIds: List<String> = emptyList(),
    val planConflict: Boolean = false,
    val arrivalRegisteredAt: String? = null,
    val sessionId: String? = null,
    val version: Int,
)

@Serializable
data class TableOptionDto(val tableIds: List<String>, val totalCapacity: Int)

@Serializable
data class AvailabilityDto(
    val available: Boolean,
    val tableOptions: List<TableOptionDto> = emptyList(),
    val alternativeStartTimes: List<String> = emptyList(),
)

@Serializable
data class QueueEntryDto(val position: Int, val reservation: ReservationDto, val tableOptions: List<TableOptionDto> = emptyList())

@Serializable
data class ReservationCreateRequest(
    val startAt: String,
    val endAt: String,
    val partySize: Int,
    val customer: ReservationCustomerDto,
    val notes: String? = null,
)

@Serializable
data class CheckInRequest(
    val tableIds: List<String>,
    val guestCount: Int? = null,
    val overridePriority: Boolean? = null,
    val overrideReason: String? = null,
)

@Serializable
data class ReasonRequest(val reason: String)

@Serializable
data class CustomerProfileDto(
    val notes: String? = null,
    val preferences: List<String> = emptyList(),
    val visitCount: Int = 0,
    val lastVisitAt: String? = null,
)

/** Cliente de la sucursal (CU-1); cada sucursal solo ve los suyos (CU-2). */
@Serializable
data class CustomerDto(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val anonymized: Boolean = false,
    val profile: CustomerProfileDto = CustomerProfileDto(),
    val version: Int,
)

@Serializable
data class CustomerCreateRequest(
    val name: String,
    val phone: String,
    val email: String? = null,
    val notes: String? = null,
    val preferences: List<String>? = null,
)

/** Solo los campos que cambian; con explicitNulls = false los null no se envían. */
@Serializable
data class CustomerUpdateRequest(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    val preferences: List<String>? = null,
)

/** CU-3: por POST, para que el teléfono no viaje en la URL. */
@Serializable
data class CustomerSearchRequest(val phone: String? = null, val name: String? = null)

@Serializable
data class MeData(val user: UserDto, val memberships: List<MembershipDto>)

/** D-37: el gerente escribe sus datos en este dispositivo. La contraseña nunca se guarda ni se registra. */
@Serializable
data class ApprovalRequest(
    val approverEmail: String,
    val approverPassword: String,
    val action: String,
    val resourceIds: List<String>,
    val reason: String,
) {
    override fun toString(): String = "ApprovalRequest(action=$action, resourceIds=$resourceIds)"
}

@Serializable
data class ApprovalData(val approvalToken: String, val expiresIn: Int)

/** Usuario global; INVITED mientras no define su contraseña (U-2). */
@Serializable
data class MemberUserDto(val id: String, val email: String, val name: String, val status: String, val version: Int)

/** Empleado de una sucursal: usuario más su membresía (U-1, U-4). */
@Serializable
data class MemberDto(val user: MemberUserDto, val branchId: String, val role: String, val active: Boolean, val version: Int)

@Serializable
data class MemberCreateRequest(val email: String, val name: String, val role: String)

/** El enlace solo llega si esta alta creó al usuario, y se muestra una sola vez. */
@Serializable
data class MemberCreatedDto(val member: MemberDto, val setupUrl: String? = null, val setupExpiresAt: String? = null)

@Serializable
data class MemberRoleRequest(val role: String)

@Serializable
data class PasswordSetupLinkDto(val setupUrl: String, val expiresAt: String)

@Serializable
data class PasswordForgotRequest(val email: String)

/** Las contraseñas nunca se registran. */
@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String) {
    override fun toString(): String = "PasswordChangeRequest(***)"
}

/** Fondo inicial; el motivo solo si difiere de lo que dejó el turno anterior (CASH_SHIFT_REASON_REQUIRED). */
@Serializable
data class CashShiftOpenRequest(val cashRegisterId: String, val openingAmount: MoneyDto, val openingDifferenceReason: String? = null)

@Serializable
data class CashMovementDto(
    val id: String,
    val cashShiftId: String,
    val type: String,
    val amount: MoneyDto,
    val reason: String,
    val createdAt: String,
)

/** DEPOSIT o WITHDRAWAL, mayor que 0. */
@Serializable
data class CashMovementCreateRequest(val type: String, val amount: MoneyDto, val reason: String)

/** Lo dejado en el cajón no puede superar lo contado; el motivo, obligatorio si hay diferencia. */
@Serializable
data class CashShiftCloseRequest(val countedCash: MoneyDto, val cashLeftInDrawer: MoneyDto, val differenceReason: String? = null)

@Serializable
data class CashMethodTotalDto(val method: String, val sales: MoneyDto, val tips: MoneyDto, val refunds: MoneyDto, val tipsRefunded: MoneyDto)

/** Pagos y reembolsos sin resolver: impiden cerrar el turno. */
@Serializable
data class CashUnresolvedDto(val pendingPayments: Int = 0, val unknownPayments: Int = 0, val pendingRefunds: Int = 0, val unknownRefunds: Int = 0) {
    val total: Int get() = pendingPayments + unknownPayments + pendingRefunds + unknownRefunds
}

/** `frozen` es true solo con el turno cerrado; en CLOSING el esperado aún cambia si se resuelve un pago pendiente. */
@Serializable
data class CashShiftSummaryDto(
    val cashShiftId: String,
    val status: String,
    val openingAmount: MoneyDto,
    val deposits: MoneyDto,
    val withdrawals: MoneyDto,
    val expectedCash: MoneyDto,
    val countedCash: MoneyDto? = null,
    val difference: MoneyDto? = null,
    val byMethod: List<CashMethodTotalDto> = emptyList(),
    val unresolved: CashUnresolvedDto = CashUnresolvedDto(),
    val frozen: Boolean = false,
)

/** Reembolso del pago completo, con su propina; el efectivo queda CONFIRMED al crear (P-7). */
@Serializable
data class RefundDto(
    val id: String,
    val paymentId: String,
    val checkId: String,
    val cashShiftId: String,
    val status: String,
    val amount: MoneyDto,
    val tip: MoneyDto,
    val reference: String? = null,
    val reason: String,
    val createdAt: String,
    val version: Int,
)

/** El reembolso se registra en un turno OPEN, no necesariamente el del pago. */
@Serializable
data class RefundCreateRequest(val cashShiftId: String, val reason: String)

/** Resultado de un reembolso PENDING; la referencia es obligatoria en CONFIRMED. */
@Serializable
data class RefundResultRequest(val outcome: String, val reference: String? = null)

/** Resolver un pago o reembolso UNKNOWN: CONFIRMED (con referencia) o FAILED, siempre con motivo. */
@Serializable
data class ReconcileRequest(val outcome: String, val reference: String? = null, val reason: String)

@Serializable
data class PrinterDto(val id: String, val code: String, val name: String, val status: String)

/** Otra impresora si la original no funciona; sin ella, se reintenta en la misma. */
@Serializable
data class PrintJobRetryRequest(val printerId: String? = null)

/** Un trabajo UNKNOWN: ¿salió impreso o no? */
@Serializable
data class PrintJobResolveRequest(val outcome: String, val reason: String)

/** Cuenta vacía en la sesión, para dividir (C-1, C-4). */
@Serializable
data class CheckCreateRequest(val sessionId: String, val label: String? = null)

/** C-5: misma sesión; la versión del destino va en el cuerpo y la del origen en If-Match. */
@Serializable
data class CheckMoveUnitsRequest(val unitIds: List<String>, val targetCheckId: String, val targetCheckVersion: Int)

/** C-7: solo cortesía de la unidad completa. Sin valor por omisión: el serializador no enviaría `type`. */
@Serializable
data class CheckAdjustmentRequest(val unitId: String, val type: String, val reason: String)
