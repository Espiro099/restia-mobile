package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.DeviceDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.devices.DeviceClosing
import com.restia.mobile.data.devices.DevicesRepository
import com.restia.mobile.ui.devices.ClosingState
import com.restia.mobile.ui.devices.DevicePermissions
import com.restia.mobile.ui.devices.DevicesViewModel
import com.restia.mobile.ui.devices.canClose
import com.restia.mobile.ui.devices.groupCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val THIS = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"
private const val OLD = "33333333-3333-4333-8333-333333333333"

private fun deviceJson(id: String, status: String, name: String) =
    """{"id":"$id","branchId":"b1","type":"ANDROID","status":"$status","displayName":"$name","enrolledAt":"2026-11-10T15:00:00Z",
    "lastSeenAt":"2026-11-18T17:00:00Z","version":1}"""

class DevicesPureTest {
    private fun device(id: String, status: String = "ACTIVE") = DeviceDto(id, "b1", status, "Equipo")

    @Test
    fun `solo se cierran los activos y nunca este telefono`() {
        assertTrue(canClose(device(OTHER), THIS))
        assertFalse(canClose(device(THIS), THIS))
        // El id llega en minúsculas, pero no se confía en eso.
        assertFalse(canClose(device(THIS), THIS.uppercase()))
        assertFalse(canClose(device(OTHER, "BLOCKED"), THIS))
        assertFalse(canClose(device(OTHER, "RETIRED"), THIS))
        assertTrue(canClose(device(OTHER), null))
    }

    @Test
    fun `el codigo se muestra en grupos de cuatro`() {
        assertEquals("ABCD EFGH JKMN", groupCode("ABCDEFGHJKMN"))
    }

    @Test
    fun `el motivo pide al menos tres letras sin contar espacios`() {
        val c = ClosingState(device(OTHER), DeviceClosing.BLOCK)
        assertFalse(c.copy(reason = " ab ").canSubmit)
        assertTrue(c.copy(reason = "Robado").canSubmit)
        assertFalse(c.copy(reason = "Robado", submitting = true).canSubmit)
    }

    @Test
    fun `un dispositivo de la respuesta de alta sigue leyendose sin los campos nuevos`() {
        val d = ApiJson.decodeFromString(DeviceDto.serializer(), """{"id":"$OTHER","branchId":"b1","status":"ACTIVE","displayName":"Caja"}""")
        assertNull(d.lastSeenAt)
        assertEquals(0, d.version)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val writes = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private val answers = ArrayDeque<MockResponse>()

    @Volatile
    private var otherStatus = "ACTIVE"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.requestUrl!!.encodedPath == "/auth/sessions") {
                    // La sesión actual dice cuál es este teléfono.
                    return MockResponse().setBody(
                        """{"data":[{"id":"s1","clientType":"ANDROID","status":"ACTIVE","current":true,"deviceId":"$THIS","createdAt":"2026-11-18T10:00:00Z",
                        "lastActivityAt":"2026-11-18T17:00:00Z","absoluteExpiresAt":"2026-11-25T10:00:00Z","revokedAt":null}],
                        "meta":{"pagination":{"page":1,"perPage":50,"total":1,"lastPage":1}}}""",
                    )
                }
                if (request.method == "GET") {
                    // El servidor ordena por alta; los cerrados pueden quedar antes que los activos.
                    return MockResponse().setBody(
                        """{"data":[${deviceJson(OLD, "RETIRED", "Tableta vieja")},${deviceJson(OTHER, otherStatus, "Caja")},${deviceJson(THIS, "ACTIVE", "Gerente")}],
                        "meta":{"pagination":{"page":1,"perPage":100,"total":3,"lastPage":1}}}""",
                    )
                }
                writes += request to request.body.readUtf8()
                return answers.removeFirst()
            }
        }
        server.start()
        // 127.0.0.1: en Linux "localhost" también es ::1 (ver OpenTableTest).
        api = Retrofit.Builder().baseUrl("http://127.0.0.1:${server.port}/")
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun vm(enroll: Boolean = true, manage: Boolean = true): DevicesViewModel {
        val vm = DevicesViewModel(DevicesRepository(api), "b1", DevicePermissions(enroll, manage))
        vm.load()
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `este telefono arriba, luego los activos`() {
        val vm = vm()
        assertEquals(listOf(THIS, OTHER, OLD), vm.state.value.devices.map { it.id })
        assertEquals(THIS, vm.state.value.thisDevice)
    }

    @Test
    fun `genera el codigo y lo olvida al cerrar`() {
        val vm = vm()
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"code":"ABCDEFGHJKMN","expiresAt":"2026-11-18T18:15:00Z"}}""")
        vm.generateCode()
        waitFor { vm.state.value.code != null }
        assertEquals("/branches/b1/devices/enrollment-codes", writes.single().first.requestUrl!!.encodedPath)
        vm.dismissCode()
        assertNull(vm.state.value.code)
    }

    @Test
    fun `demasiados codigos se informa sin abrir el dialogo`() {
        val vm = vm()
        answers += MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("""{"error":{"code":"RATE_LIMITED","message":"x"}}""")
        vm.generateCode()
        waitFor { vm.state.value.codeFailure != null }
        assertEquals(429, (vm.state.value.codeFailure as ApiFailure.Server).status)
        assertNull(vm.state.value.code)
        assertFalse(vm.state.value.generating)
    }

    @Test
    fun `bloquear manda el motivo y recarga la lista`() {
        val vm = vm()
        answers += MockResponse().setResponseCode(204)
        vm.startClosing(vm.state.value.devices.first { it.id == OTHER }, DeviceClosing.BLOCK)
        vm.setReason("  Se perdió  ")
        otherStatus = "BLOCKED"
        vm.confirmClosing()
        waitFor { vm.state.value.closed == DeviceClosing.BLOCK && vm.state.value.devices.first { it.id == OTHER }.status == "BLOCKED" }
        val (request, body) = writes.single()
        assertEquals("/branches/b1/devices/$OTHER/block", request.requestUrl!!.encodedPath)
        assertEquals("""{"reason":"Se perdió"}""", body)
        assertNull(vm.state.value.closing)
    }

    @Test
    fun `un error al retirar deja el dialogo abierto con el motivo`() {
        val vm = vm()
        answers += MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"x"}}""")
        vm.startClosing(vm.state.value.devices.first { it.id == OTHER }, DeviceClosing.RETIRE)
        vm.setReason("Ya no se usa")
        vm.confirmClosing()
        waitFor { vm.state.value.closing?.failure != null }
        assertEquals("/branches/b1/devices/$OTHER/retire", writes.single().first.requestUrl!!.encodedPath)
        assertEquals("Ya no se usa", vm.state.value.closing!!.reason)
        assertFalse(vm.state.value.closing!!.submitting)
    }

    @Test
    fun `sin permiso o sobre este telefono no se abre el cierre`() {
        val vm = vm(enroll = false, manage = false)
        vm.startClosing(vm.state.value.devices.first { it.id == OTHER }, DeviceClosing.BLOCK)
        assertNull(vm.state.value.closing)
        vm.generateCode()
        assertFalse(vm.state.value.generating)
        val manager = vm()
        manager.startClosing(manager.state.value.devices.first { it.id == THIS }, DeviceClosing.RETIRE)
        assertNull(manager.state.value.closing)
        assertTrue(writes.isEmpty())
    }
}
