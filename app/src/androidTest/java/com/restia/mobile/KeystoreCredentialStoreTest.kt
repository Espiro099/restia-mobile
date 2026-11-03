package com.restia.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.restia.mobile.core.security.KeystoreCredentialStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreCredentialStore(context)

    @After
    fun tearDown() = store.wipeAll()

    private fun rawFile() = context.getSharedPreferences("restia_credentials", Context.MODE_PRIVATE).all.values.joinToString()

    @Test
    fun guardaYLeeDesdeOtraInstancia() {
        store.saveDevice("uuid", "credencial", "sucursal")
        store.saveRefreshToken("refresh")
        val other = KeystoreCredentialStore(context)
        assertEquals("uuid", other.deviceUuid)
        assertEquals("credencial", other.deviceCredential)
        assertEquals("sucursal", other.branchId)
        assertEquals("refresh", other.refreshToken)
    }

    @Test
    fun noGuardaEnClaro() {
        store.saveDevice("uuid-visible", "credencial-visible", "sucursal-visible")
        store.saveRefreshToken("refresh-visible")
        val raw = rawFile()
        assertFalse(raw.contains("visible"))
    }

    @Test
    fun mismoValorCifraDistinto() {
        store.saveRefreshToken("igual")
        val first = rawFile()
        store.saveRefreshToken("igual")
        assertNotEquals(first, rawFile())
    }

    @Test
    fun cerrarSesionConservaElDispositivo() {
        store.saveDevice("uuid", "credencial", "sucursal")
        store.saveRefreshToken("refresh")
        store.clearSession()
        assertNull(store.refreshToken)
        assertEquals("credencial", store.deviceCredential)
    }

    @Test
    fun borrarTodoEliminaElDispositivo() {
        store.saveDevice("uuid", "credencial", "sucursal")
        store.wipeAll()
        assertNull(store.deviceUuid)
        assertNull(store.deviceCredential)
    }

    @Test
    fun valorDanadoSeLeeComoAusente() {
        context.getSharedPreferences("restia_credentials", Context.MODE_PRIVATE).edit().putString("refresh_token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertNull(store.refreshToken)
    }
}
