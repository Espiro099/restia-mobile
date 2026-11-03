package com.restia.mobile.core.security

/** Credenciales persistentes. Nunca guarda la contraseña ni el access token (D-36, 5.4). */
interface CredentialStore {
    val deviceUuid: String?
    val deviceCredential: String?
    val refreshToken: String?
    val pendingLogoutToken: String?

    fun saveDevice(deviceUuid: String, deviceCredential: String)
    fun saveRefreshToken(token: String)
    fun savePendingLogoutToken(token: String?)
    fun clearSession()
    fun wipeAll()
}

/** El access token solo vive en memoria. */
class AccessTokenHolder {
    @Volatile
    var token: String? = null
}
