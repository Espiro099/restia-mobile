package com.restia.mobile.core.network

import kotlinx.serialization.Serializable
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
data class DeviceDto(val id: String, val branchId: String, val status: String, val displayName: String)

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
)

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
    val tipTotal: MoneyDto,
    val balance: MoneyDto,
    val version: Int,
)

@Serializable
data class CashShiftDto(val id: String, val cashRegisterId: String, val status: String, val openedAt: String? = null)

@Serializable
data class CashRegisterDto(val id: String, val code: String, val name: String, val status: String)

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
data class PrintJobDto(val id: String, val type: String, val status: String)

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
