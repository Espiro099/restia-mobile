package com.restia.mobile

import com.restia.mobile.core.security.CredentialStore

class FakeCredentialStore(
    override var deviceUuid: String? = "00000000-0000-4000-8000-000000000001",
    override var deviceCredential: String? = "credencial-de-prueba-de-al-menos-32-caracteres",
    override var branchId: String? = "b1",
    override var refreshToken: String? = "refresh-1",
    override var pendingLogoutToken: String? = null,
) : CredentialStore {
    override fun saveDevice(deviceUuid: String, deviceCredential: String, branchId: String) {
        this.branchId = branchId
        this.deviceUuid = deviceUuid
        this.deviceCredential = deviceCredential
    }

    override fun saveRefreshToken(token: String) {
        refreshToken = token
    }

    override fun savePendingLogoutToken(token: String?) {
        pendingLogoutToken = token
    }

    override fun clearSession() {
        refreshToken = null
    }

    override fun wipeAll() {
        deviceUuid = null
        deviceCredential = null
        branchId = null
        refreshToken = null
        pendingLogoutToken = null
    }
}
