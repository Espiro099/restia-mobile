package com.restia.mobile.data.account

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.PasswordChangeRequest
import com.restia.mobile.core.network.PasswordForgotRequest
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.network.parseError
import retrofit2.Response

class PasswordRepository(private val publicApi: PublicAuthApi, private val sessionApi: SessionApi) {
    /** El backend responde igual exista o no el correo; el enlace llega por correo y se usa en la página web. */
    suspend fun forgot(email: String): ApiResult<Unit> = noContent { publicApi.forgotPassword(PasswordForgotRequest(email)) }

    suspend fun change(current: String, new: String): ApiResult<Unit> =
        noContent { sessionApi.changePassword(PasswordChangeRequest(current, new)) }

    private suspend fun noContent(block: suspend () -> Response<Unit>): ApiResult<Unit> =
        when (val r = apiCall(block)) {
            is ApiResult.Failure -> r
            is ApiResult.Success -> if (r.value.isSuccessful) {
                ApiResult.Success(Unit)
            } else {
                ApiResult.Failure(parseError(r.value.code(), r.value.errorBody()?.string(), r.value.headers()["Retry-After"]))
            }
        }
}
