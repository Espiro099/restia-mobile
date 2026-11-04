package com.restia.mobile

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.restia.mobile.core.security.EncryptedFileStore
import com.restia.mobile.core.security.KeystoreCredentialStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val blobs = EncryptedFileStore(context)
    private val store = KeystoreCredentialStore(context, blobs)

    @After
    fun tearDown() {
        store.wipeAll()
        blobs.wipe()
    }

    private fun rawFile() = context.getSharedPreferences("restia_credentials", Context.MODE_PRIVATE).all.values.joinToString()

    @Test
    fun guardaYLeeDesdeOtraInstancia() {
        store.saveDevice("uuid", "credencial", "sucursal")
        store.saveRefreshToken("refresh")
        val other = KeystoreCredentialStore(context, blobs)
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
    fun archivoNoGuardaEnClaroYSobreviveAOtraInstancia() {
        blobs.write("draft-u1_s1", "comanda-visible")
        assertEquals("comanda-visible", EncryptedFileStore(context).read("draft-u1_s1"))
        val raw = java.io.File(context.noBackupFilesDir, "secure/draft-u1_s1").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("comanda-visible"))
        assertEquals(listOf("draft-u1_s1"), blobs.names("draft-"))
    }

    @Test
    fun bloquearElDispositivoBorraBorradoresYCache() {
        blobs.write("draft-u1_s1", "comanda")
        blobs.write("menu-b1", "menu")
        store.saveDevice("uuid", "credencial", "sucursal")
        store.wipeAll()
        assertNull(blobs.read("draft-u1_s1"))
        assertTrue(blobs.names("").isEmpty())
    }

    @Test
    fun cerrarSesionConservaLosBorradores() {
        blobs.write("draft-u1_s1", "comanda")
        store.saveRefreshToken("refresh")
        store.clearSession()
        assertEquals("comanda", blobs.read("draft-u1_s1"))
    }

    @Test
    fun archivoDanadoSeLeeComoAusente() {
        java.io.File(context.noBackupFilesDir, "secure").mkdirs()
        java.io.File(context.noBackupFilesDir, "secure/roto").writeBytes(ByteArray(40))
        assertNull(blobs.read("roto"))
    }

    @Test
    fun valorDanadoSeLeeComoAusente() {
        context.getSharedPreferences("restia_credentials", Context.MODE_PRIVATE).edit().putString("refresh_token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertNull(store.refreshToken)
    }
}
