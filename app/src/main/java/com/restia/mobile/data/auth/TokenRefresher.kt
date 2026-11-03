package com.restia.mobile.data.auth

import com.restia.mobile.core.network.MobileRefreshRequest
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.parseError
import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.core.security.CredentialStore
import java.io.IOException
import kotlinx.serialization.SerializationException

sealed interface RefreshResult {
    data class Success(val accessToken: String) : RefreshResult

    /** Sin red, límite de uso o error del servidor: se conserva la sesión. */
    data object Unavailable : RefreshResult
    data object Rejected : RefreshResult
    data object DeviceBlocked : RefreshResult
}

/** Una sola renovación a la vez: el refresh token rota y no se debe usar dos veces (D-36, 5.3). */
class TokenRefresher(
    private val store: CredentialStore,
    private val accessToken: AccessTokenHolder,
    private val api: PublicAuthApi,
    private val state: AuthStateHolder,
) {
    @Synchronized
    fun refresh(sentToken: String?): RefreshResult {
        val current = accessToken.token
        if (current != null && current != sentToken) return RefreshResult.Success(current)

        val deviceUuid = store.deviceUuid
        val refreshToken = store.refreshToken
        if (deviceUuid == null || refreshToken == null) return reject()

        val response = try {
            api.refresh(MobileRefreshRequest(deviceUuid, refreshToken)).execute()
        } catch (_: IOException) {
            return RefreshResult.Unavailable
        } catch (_: SerializationException) {
            // Respuesta ilegible: no se sabe si rotó, así que se conserva la sesión.
            return RefreshResult.Unavailable
        }
        val tokens = response.body()?.data
        if (response.isSuccessful && tokens != null) {
            store.saveRefreshToken(tokens.refreshToken)
            accessToken.token = tokens.accessToken
            return RefreshResult.Success(tokens.accessToken)
        }
        val error = parseError(response.code(), response.errorBody()?.string(), null)
        return when {
            error.code == DEVICE_BLOCKED -> blocked()
            error.status == 429 || error.status >= 500 -> RefreshResult.Unavailable
            else -> reject()
        }
    }

    /** El servidor dijo que el dispositivo ya no está autorizado: se borran sus datos locales. */
    fun blocked(): RefreshResult {
        accessToken.token = null
        store.wipeAll()
        state.notify(SessionNotice.DEVICE_BLOCKED)
        state.set(AuthState.NeedsEnrollment)
        return RefreshResult.DeviceBlocked
    }

    private fun reject(): RefreshResult {
        accessToken.token = null
        store.clearSession()
        if (state.state.value !is AuthState.NeedsEnrollment) {
            state.notify(SessionNotice.SESSION_EXPIRED)
            state.set(AuthState.NeedsLogin)
        }
        return RefreshResult.Rejected
    }

    companion object {
        const val DEVICE_BLOCKED = "DEVICE_BLOCKED"
    }
}
