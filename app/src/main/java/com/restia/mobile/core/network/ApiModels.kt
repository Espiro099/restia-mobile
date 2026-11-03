package com.restia.mobile.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class Envelope<T>(val data: T)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String, val requestId: String? = null)

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
data class MeData(val user: UserDto, val memberships: List<MembershipDto>)
