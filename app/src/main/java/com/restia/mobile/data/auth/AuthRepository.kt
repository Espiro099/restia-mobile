package com.restia.mobile.data.auth

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.DeviceEnrollRequest
import com.restia.mobile.core.network.MeData
import com.restia.mobile.core.network.MobileLoginRequest
import com.restia.mobile.core.network.MobileRefreshRequest
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.core.security.CredentialStore
import com.restia.mobile.core.storage.BlobStore
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val store: CredentialStore,
    private val accessToken: AccessTokenHolder,
    private val publicApi: PublicAuthApi,
    private val sessionApi: SessionApi,
    private val refresher: TokenRefresher,
    private val stateHolder: AuthStateHolder,
    private val cache: BlobStore? = null,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    val state = stateHolder.state
    val notice = stateHolder.notice

    suspend fun start() {
        stateHolder.set(AuthState.Loading)
        if (store.deviceUuid == null || store.branchId == null) {
            store.wipeAll()
            stateHolder.set(AuthState.NeedsEnrollment)
            return
        }
        flushPendingLogout()
        if (store.refreshToken == null) {
            stateHolder.set(AuthState.NeedsLogin)
            return
        }
        when (withContext(Dispatchers.IO) { refresher.refresh(null) }) {
            is RefreshResult.Success -> loadMe()
            RefreshResult.Unavailable -> setOffline()
            RefreshResult.Rejected, RefreshResult.DeviceBlocked -> Unit
        }
    }

    /** Cada intento usa otro deviceUuid: un alta perdida exige código nuevo (contrato de enroll). */
    suspend fun enroll(code: String, displayName: String): ApiFailure? {
        val uuid = newUuid()
        return when (val r = apiCall { publicApi.enroll(DeviceEnrollRequest(code, uuid, displayName)) }) {
            is ApiResult.Success -> {
                store.saveDevice(uuid, r.value.data.deviceCredential, r.value.data.device.branchId)
                stateHolder.notify(null)
                stateHolder.set(AuthState.NeedsLogin)
                null
            }
            is ApiResult.Failure -> r.failure
        }
    }

    suspend fun login(email: String, password: String): ApiFailure? {
        val uuid = store.deviceUuid
        val credential = store.deviceCredential
        if (uuid == null || credential == null) {
            stateHolder.set(AuthState.NeedsEnrollment)
            return null
        }
        return when (val r = apiCall { publicApi.login(MobileLoginRequest(email, password, uuid, credential)) }) {
            is ApiResult.Success -> {
                store.saveRefreshToken(r.value.data.refreshToken)
                accessToken.token = r.value.data.accessToken
                // El login en este dispositivo reemplaza a la sesión anterior (D-36, 5.5).
                store.savePendingLogoutToken(null)
                stateHolder.notify(null)
                loadMe()
                null
            }
            is ApiResult.Failure -> {
                val failure = r.failure
                when ((failure as? ApiFailure.Server)?.code) {
                    TokenRefresher.DEVICE_BLOCKED -> refresher.blocked()
                    DEVICE_NOT_ENROLLED -> {
                        store.wipeAll()
                        stateHolder.set(AuthState.NeedsEnrollment)
                    }
                }
                failure
            }
        }
    }

    suspend fun logout() {
        val refreshToken = store.refreshToken
        val failed = when (val r = apiCall { sessionApi.logout() }) {
            is ApiResult.Success -> !r.value.isSuccessful && r.value.code() != 401 && r.value.code() >= 500
            is ApiResult.Failure -> r.failure == ApiFailure.Network
        }
        // Sin conexión se borran los tokens y la revocación queda pendiente (D-36, 5.7).
        if (failed && refreshToken != null) store.savePendingLogoutToken(refreshToken)
        accessToken.token = null
        store.clearSession()
        withContext(Dispatchers.IO) { cache?.delete(ME) }
        if (state.value !is AuthState.NeedsEnrollment) stateHolder.set(AuthState.NeedsLogin)
    }

    private suspend fun loadMe() {
        when (val r = apiCall { sessionApi.me() }) {
            is ApiResult.Success -> {
                withContext(Dispatchers.IO) { cache?.write(ME, ApiJson.encodeToString(MeData.serializer(), r.value.data)) }
                stateHolder.set(store.branchId?.let { AuthState.Authenticated(r.value.data, it) } ?: AuthState.NeedsEnrollment)
            }
            // Si la sesión o el dispositivo se perdieron, el refresher ya cambió el estado.
            is ApiResult.Failure -> if (store.refreshToken != null) setOffline()
        }
    }

    /** Sin red, entra con los datos conocidos para capturar borradores (A-7); el backend revalida al reconectar. */
    private suspend fun setOffline() {
        val me = withContext(Dispatchers.IO) {
            cache?.read(ME)?.let {
                try {
                    ApiJson.decodeFromString(MeData.serializer(), it)
                } catch (_: SerializationException) {
                    null
                }
            }
        }
        val branchId = store.branchId
        stateHolder.set(if (me != null && branchId != null) AuthState.Authenticated(me, branchId) else AuthState.Offline)
    }

    private suspend fun flushPendingLogout() {
        val pending = store.pendingLogoutToken ?: return
        val uuid = store.deviceUuid ?: return
        val outcome = withContext(Dispatchers.IO) {
            try {
                val response = publicApi.refresh(MobileRefreshRequest(uuid, pending)).execute()
                if (response.isSuccessful) response.body()?.data?.accessToken else ""
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            }
        } ?: return
        if (outcome.isNotEmpty()) apiCall { publicApi.logoutWith("Bearer $outcome") }
        store.savePendingLogoutToken(null)
    }

    private companion object {
        const val DEVICE_NOT_ENROLLED = "DEVICE_NOT_ENROLLED"
        const val ME = "me"
    }
}
