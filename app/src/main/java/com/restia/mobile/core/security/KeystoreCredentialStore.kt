package com.restia.mobile.core.security

import android.content.Context
import android.util.Base64
import com.restia.mobile.core.storage.BlobStore
import java.security.GeneralSecurityException

/** Cifra cada valor con Android Keystore. Borrar el dispositivo borra también caché y borradores. */
class KeystoreCredentialStore(context: Context, private val blobs: BlobStore) : CredentialStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val deviceUuid get() = read(DEVICE_UUID)
    override val deviceCredential get() = read(DEVICE_CREDENTIAL)
    override val branchId get() = read(BRANCH_ID)
    override val refreshToken get() = read(REFRESH_TOKEN)
    override val pendingLogoutToken get() = read(PENDING_LOGOUT)

    override fun saveDevice(deviceUuid: String, deviceCredential: String, branchId: String) {
        prefs.edit()
            .putString(DEVICE_UUID, encrypt(deviceUuid))
            .putString(DEVICE_CREDENTIAL, encrypt(deviceCredential))
            .putString(BRANCH_ID, encrypt(branchId))
            .commit()
    }

    override fun saveRefreshToken(token: String) {
        prefs.edit().putString(REFRESH_TOKEN, encrypt(token)).commit()
    }

    override fun savePendingLogoutToken(token: String?) {
        val edit = prefs.edit()
        if (token == null) edit.remove(PENDING_LOGOUT) else edit.putString(PENDING_LOGOUT, encrypt(token))
        edit.commit()
    }

    override fun clearSession() {
        prefs.edit().remove(REFRESH_TOKEN).commit()
    }

    override fun wipeAll() {
        prefs.edit().clear().commit()
        blobs.wipe()
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            String(KeystoreCipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encrypt(value: String): String =
        Base64.encodeToString(KeystoreCipher.encrypt(value.toByteArray()), Base64.NO_WRAP)

    private companion object {
        const val FILE = "restia_credentials"
        const val DEVICE_UUID = "device_uuid"
        const val DEVICE_CREDENTIAL = "device_credential"
        const val BRANCH_ID = "branch_id"
        const val REFRESH_TOKEN = "refresh_token"
        const val PENDING_LOGOUT = "pending_logout_token"
    }
}
