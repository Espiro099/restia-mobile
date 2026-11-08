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
data class TableSessionDto(val id: String, val status: String, val guestCount: Int, val version: Int)

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
