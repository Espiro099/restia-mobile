package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.BearerInterceptor
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TokenAuthenticator
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.security.AccessTokenHolder
import com.restia.mobile.data.auth.AuthRepository
import com.restia.mobile.data.auth.AuthState
import com.restia.mobile.data.auth.AuthStateHolder
import com.restia.mobile.data.auth.SessionNotice
import com.restia.mobile.data.auth.TokenRefresher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val ME = """{"data":{"user":{"id":"u1","name":"Ana","email":"a@restia.test"},"memberships":[{"branch":{"id":"b1","name":"Centro"},"role":"WAITER","permissions":[]}]},"meta":{}}"""

private fun tokens(access: String, refresh: String) =
    """{"data":{"accessToken":"$access","accessTokenExpiresIn":900,"refreshToken":"$refresh"},"meta":{}}"""

private fun error(code: String) = """{"error":{"code":"$code","message":"mensaje $code","requestId":"r"}}"""

class AuthFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeCredentialStore
    private lateinit var access: AccessTokenHolder
    private lateinit var state: AuthStateHolder
    private lateinit var repo: AuthRepository
    private lateinit var sessionApi: SessionApi
    private val blobs = InMemoryBlobStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeCredentialStore()
        access = AccessTokenHolder()
        state = AuthStateHolder()
        val url = server.url("/")
        val converter = ApiJson.asConverterFactory("application/json".toMediaType())
        val base = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val publicApi = Retrofit.Builder().baseUrl(url).client(base).addConverterFactory(converter).build().create(PublicAuthApi::class.java)
        val refresher = TokenRefresher(store, access, publicApi, state)
        val client = base.newBuilder().addInterceptor(BearerInterceptor(access, refresher)).authenticator(TokenAuthenticator(refresher)).build()
        sessionApi = Retrofit.Builder().baseUrl(url).client(client).addConverterFactory(converter).build().create(SessionApi::class.java)
        repo = AuthRepository(store, access, publicApi, sessionApi, refresher, state, blobs, newUuid = { "uuid-${++uuids}" })
    }

    private var uuids = 0

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun ok(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `sin dispositivo pide el alta`() = runBlocking {
        store.wipeAll()
        repo.start()
        assertEquals(AuthState.NeedsEnrollment, state.state.value)
    }

    @Test
    fun `dispositivo sin sucursal guardada vuelve al alta`() = runBlocking {
        store.branchId = null
        repo.start()
        assertEquals(AuthState.NeedsEnrollment, state.state.value)
        assertNull(store.deviceCredential)
    }

    @Test
    fun `alta guarda la credencial y pide login`() = runBlocking {
        store.wipeAll()
        route { ok("""{"data":{"device":{"id":"d","branchId":"b","status":"ACTIVE","displayName":"x"},"deviceCredential":"cred-nueva"},"meta":{}}""", 201) }
        assertNull(repo.enroll("CODIGO123", "Tablet"))
        assertEquals("uuid-1", store.deviceUuid)
        assertEquals("cred-nueva", store.deviceCredential)
        assertEquals("b", store.branchId)
        assertEquals(AuthState.NeedsLogin, state.state.value)
    }

    @Test
    fun `alta rechazada no guarda nada y el reintento usa otro uuid`() = runBlocking {
        store.wipeAll()
        route { ok(error("DEVICE_ALREADY_ENROLLED"), 409) }
        val failure = repo.enroll("CODIGO123", "Tablet") as ApiFailure.Server
        assertEquals("DEVICE_ALREADY_ENROLLED", failure.code)
        assertNull(store.deviceUuid)
        repo.enroll("CODIGO456", "Tablet")
        val bodies = listOf(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
        assertTrue(bodies[0].contains("uuid-1") && bodies[1].contains("uuid-2"))
    }

    @Test
    fun `login exitoso guarda el refresh, deja el access en memoria y carga al usuario`() = runBlocking {
        store.refreshToken = null
        route { if (it.path == "/auth/me") ok(ME) else ok(tokens("access-1", "refresh-2"), 201) }
        assertNull(repo.login("a@restia.test", "secreto"))
        assertEquals("refresh-2", store.refreshToken)
        assertEquals("access-1", access.token)
        assertEquals("b1", (state.state.value as AuthState.Authenticated).branchId)
        server.takeRequest()
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `credenciales incorrectas no intentan renovar`() = runBlocking {
        store.refreshToken = null
        route { ok(error("AUTHENTICATION_INVALID"), 401) }
        val failure = repo.login("a@restia.test", "mala") as ApiFailure.Server
        assertEquals("AUTHENTICATION_INVALID", failure.code)
        assertEquals(1, server.requestCount)
        assertNotNull(store.deviceUuid)
    }

    @Test
    fun `dispositivo no registrado vuelve al alta`() = runBlocking {
        route { ok(error("DEVICE_NOT_ENROLLED"), 401) }
        repo.login("a@restia.test", "x")
        assertEquals(AuthState.NeedsEnrollment, state.state.value)
        assertNull(store.deviceCredential)
    }

    @Test
    fun `token vencido renueva y reintenta una vez - CRED-05`() = runBlocking {
        access.token = "viejo"
        route {
            when {
                it.path == "/auth/mobile/refresh" -> ok(tokens("nuevo", "refresh-2"))
                it.getHeader("Authorization") == "Bearer nuevo" -> ok(ME)
                else -> ok(error("AUTHENTICATION_REQUIRED"), 401)
            }
        }
        assertEquals("Ana", sessionApi.me().data.user.name)
        assertEquals("refresh-2", store.refreshToken)
        assertEquals("nuevo", access.token)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `dos peticiones con token vencido renuevan una sola vez - CRED-06`() = runBlocking {
        access.token = "viejo"
        var refreshes = 0
        route {
            when {
                it.path == "/auth/mobile/refresh" -> {
                    synchronized(this@AuthFlowTest) { refreshes++ }
                    ok(tokens("nuevo", "refresh-2")).setBodyDelay(100, TimeUnit.MILLISECONDS)
                }
                it.getHeader("Authorization") == "Bearer nuevo" -> ok(ME)
                else -> ok(error("AUTHENTICATION_REQUIRED"), 401)
            }
        }
        val results = (1..6).map { async(Dispatchers.IO) { sessionApi.me().data.user.name } }.awaitAll()
        assertEquals(List(6) { "Ana" }, results)
        assertEquals(1, refreshes)
    }

    @Test
    fun `no reintenta en bucle si el token nuevo tambien da 401`() = runBlocking {
        access.token = "viejo"
        route { if (it.path == "/auth/mobile/refresh") ok(tokens("nuevo", "r2")) else ok(error("AUTHENTICATION_REQUIRED"), 401) }
        assertEquals(false, runCatching { sessionApi.me() }.isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `renovacion rechazada cierra la sesion y conserva el dispositivo`() = runBlocking {
        route { ok(error("SESSION_REVOKED"), 401) }
        repo.start()
        assertEquals(AuthState.NeedsLogin, state.state.value)
        assertEquals(SessionNotice.SESSION_EXPIRED, state.notice.value)
        assertNull(store.refreshToken)
        assertNotNull(store.deviceCredential)
    }

    @Test
    fun `dispositivo bloqueado borra todo - CRED-12`() = runBlocking {
        route { ok(error("DEVICE_BLOCKED"), 403) }
        repo.start()
        assertEquals(AuthState.NeedsEnrollment, state.state.value)
        assertEquals(SessionNotice.DEVICE_BLOCKED, state.notice.value)
        assertNull(store.deviceCredential)
        assertNull(store.refreshToken)
    }

    @Test
    fun `bloqueo en una peticion normal tambien borra todo`() = runBlocking {
        access.token = "valido"
        route { ok(error("DEVICE_BLOCKED"), 403) }
        runCatching { sessionApi.me() }
        assertEquals(AuthState.NeedsEnrollment, state.state.value)
        assertNull(store.deviceCredential)
    }

    @Test
    fun `respuesta de renovacion ilegible conserva la sesion`() = runBlocking {
        route { ok("") }
        repo.start()
        assertEquals(AuthState.Offline, state.state.value)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun `respuesta de renovacion perdida conserva la sesion - CRED-07`() = runBlocking {
        route { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        repo.start()
        assertEquals(AuthState.Offline, state.state.value)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun `sin red y con datos conocidos entra para capturar borradores`() = runBlocking {
        blobs.write("me", ME.substringAfter("{\"data\":").substringBeforeLast(",\"meta\""))
        route { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        repo.start()
        val current = state.state.value as AuthState.Authenticated
        assertEquals("Ana", current.me.user.name)
        assertEquals("b1", current.branchId)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun `sesion revocada no entra aunque haya datos conocidos`() = runBlocking {
        blobs.write("me", ME.substringAfter("{\"data\":").substringBeforeLast(",\"meta\""))
        route { ok(error("SESSION_REVOKED"), 401) }
        repo.start()
        assertEquals(AuthState.NeedsLogin, state.state.value)
    }

    @Test
    fun `login guarda los datos del usuario y logout los borra`() = runBlocking {
        store.refreshToken = null
        route { if (it.path == "/auth/me") ok(ME) else if (it.path == "/auth/logout") MockResponse().setResponseCode(204) else ok(tokens("a", "r"), 201) }
        repo.login("a@restia.test", "x")
        assertNotNull(blobs.read("me"))
        repo.logout()
        assertNull(blobs.read("me"))
    }

    @Test
    fun `limite de uso o error del servidor al renovar conserva la sesion`() = runBlocking {
        route { ok(error("SERVICE_BUSY"), 503) }
        repo.start()
        assertEquals(AuthState.Offline, state.state.value)
        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun `logout con red revoca y borra los tokens`() = runBlocking {
        access.token = "valido"
        route { MockResponse().setResponseCode(204) }
        repo.logout()
        assertNull(access.token)
        assertNull(store.refreshToken)
        assertNull(store.pendingLogoutToken)
        assertNotNull(store.deviceCredential)
        assertEquals(AuthState.NeedsLogin, state.state.value)
    }

    @Test
    fun `logout sin red borra tokens y la revocacion queda pendiente`() = runBlocking {
        access.token = "valido"
        route { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        repo.logout()
        assertNull(access.token)
        assertNull(store.refreshToken)
        assertEquals("refresh-1", store.pendingLogoutToken)
    }

    @Test
    fun `al volver la red envia la revocacion pendiente`() = runBlocking {
        store.refreshToken = null
        store.pendingLogoutToken = "pendiente"
        route { if (it.path == "/auth/mobile/refresh") ok(tokens("temporal", "r3")) else MockResponse().setResponseCode(204) }
        repo.start()
        server.takeRequest()
        val logout = server.takeRequest()
        assertEquals("/auth/logout", logout.path)
        assertEquals("Bearer temporal", logout.getHeader("Authorization"))
        assertNull(store.pendingLogoutToken)
        assertNull(store.refreshToken)
        assertEquals(AuthState.NeedsLogin, state.state.value)
    }

    @Test
    fun `el almacen nunca recibe la contrasena`() = runBlocking {
        store.refreshToken = null
        route { if (it.path == "/auth/me") ok(ME) else ok(tokens("a", "r"), 201) }
        repo.login("a@restia.test", "contrasena-secreta")
        val stored = listOf(store.deviceUuid, store.deviceCredential, store.refreshToken, store.pendingLogoutToken)
        assertFalse(stored.any { it?.contains("contrasena-secreta") == true })
    }
}
