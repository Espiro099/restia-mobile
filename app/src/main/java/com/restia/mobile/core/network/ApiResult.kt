package com.restia.mobile.core.network

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

sealed interface ApiFailure {
    data object Network : ApiFailure
    data object Unexpected : ApiFailure
    data class Server(
        val status: Int,
        val code: String?,
        val message: String?,
        val retryAfterSeconds: Long?,
        val details: JsonObject? = null,
    ) : ApiFailure
}

fun parseError(status: Int, body: String?, retryAfter: String?): ApiFailure.Server {
    val error = runCatching { ApiJson.decodeFromString<ErrorEnvelope>(body.orEmpty()).error }.getOrNull()
    return ApiFailure.Server(status, error?.code, error?.message, retryAfter?.toLongOrNull(), error?.details)
}

suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: HttpException) {
    val response = e.response()
    ApiResult.Failure(parseError(e.code(), response?.errorBody()?.string(), response?.headers()?.get("Retry-After")))
} catch (_: IOException) {
    ApiResult.Failure(ApiFailure.Network)
} catch (_: SerializationException) {
    ApiResult.Failure(ApiFailure.Unexpected)
}
