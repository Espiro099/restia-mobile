package com.restia.mobile.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra cada valor con AES-GCM, con una clave no exportable de Android Keystore. */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val deviceUuid get() = read(DEVICE_UUID)
    override val deviceCredential get() = read(DEVICE_CREDENTIAL)
    override val refreshToken get() = read(REFRESH_TOKEN)
    override val pendingLogoutToken get() = read(PENDING_LOGOUT)

    override fun saveDevice(deviceUuid: String, deviceCredential: String) {
        prefs.edit()
            .putString(DEVICE_UUID, encrypt(deviceUuid))
            .putString(DEVICE_CREDENTIAL, encrypt(deviceCredential))
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
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "restia_credentials"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "restia_credentials_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val DEVICE_UUID = "device_uuid"
        const val DEVICE_CREDENTIAL = "device_credential"
        const val REFRESH_TOKEN = "refresh_token"
        const val PENDING_LOGOUT = "pending_logout_token"
    }
}
