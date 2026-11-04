package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.tables.MAX_GUESTS
import com.restia.mobile.ui.tables.TablesViewModel
import com.restia.mobile.ui.tables.isOpenable
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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val SESSION = """{"data":{"id":"s1","status":"ACTIVE","guestCount":2,"version":1},"meta":{}}"""
private const val EMPTY_LIST = """{"data":[],"meta":{}}"""

private fun conflict(code: String, details: String = "") =
    MockResponse().setResponseCode(409).setBody("""{"error":{"code":"$code","message":"mensaje $code"$details,"requestId":"r"}}""")

@OptIn(ExperimentalCoroutinesApi::class)
class OpenTableTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: TablesViewModel
    private val mesa = TableDto("t1", 1, 4, null, "ACTIVE", "LIBRE")
    private val posts = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private var uuids = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = TablesViewModel(TablesRepository(api), "b1", newKey = { "clave-${++uuids}" })
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun respond(vararg answers: MockResponse) {
        val queue = ArrayDeque(answers.toList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method != "POST") return MockResponse().setBody(EMPTY_LIST)
                posts += request
                return queue.removeFirst()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun submitAndWait() {
        vm.confirmOpen()
        waitFor { vm.state.value.opening?.submitting != true }
    }

    @Test
    fun `solo se abren mesas activas libres o con posible ocupacion`() {
        assertTrue(isOpenable(mesa))
        assertTrue(isOpenable(mesa.copy(operationalState = "POSIBLE_OCUPACION")))
        assertFalse(isOpenable(mesa.copy(operationalState = "CONSUMIENDO")))
        assertFalse(isOpenable(mesa.copy(operationalState = "POR_LIMPIAR")))
        assertFalse(isOpenable(mesa.copy(status = "MAINTENANCE", operationalState = null)))
        vm.selectTable(mesa.copy(operationalState = "CONSUMIENDO"))
        assertNull(vm.state.value.opening)
    }

    @Test
    fun `personas se mantienen dentro del contrato`() {
        vm.selectTable(mesa)
        vm.setGuests(0)
        assertEquals(1, vm.state.value.opening?.guests)
        vm.setGuests(500)
        assertEquals(MAX_GUESTS, vm.state.value.opening?.guests)
    }

    @Test
    fun `abrir envia mesa, personas y clave`() {
        respond(MockResponse().setResponseCode(201).setBody(SESSION))
        vm.selectTable(mesa)
        vm.setGuests(3)
        submitAndWait()
        val post = posts.single()
        assertEquals("/branches/b1/table-sessions", post.path)
        assertEquals("clave-1", post.getHeader("Idempotency-Key"))
        assertEquals("""{"tableIds":["t1"],"guestCount":3}""", post.body.readUtf8())
        assertNull(vm.state.value.opening)
        assertEquals(1, vm.state.value.openedTableNumber)
    }

    @Test
    fun `reintentar tras perder la respuesta repite la clave`() {
        respond(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST), MockResponse().setResponseCode(201).setBody(SESSION))
        vm.selectTable(mesa)
        submitAndWait()
        assertEquals(ApiFailure.Network, vm.state.value.opening?.failure)
        submitAndWait()
        assertEquals(2, posts.size)
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertNull(vm.state.value.opening)
    }

    @Test
    fun `servidor ocupado se reintenta con la misma clave`() {
        respond(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"SERVICE_BUSY","message":"m"}}"""), MockResponse().setResponseCode(201).setBody(SESSION))
        vm.selectTable(mesa)
        submitAndWait()
        submitAndWait()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `cambiar personas usa otra clave para no recibir IDEMPOTENCY_KEY_REUSED`() {
        respond(MockResponse().setResponseCode(503).setBody("{}"), MockResponse().setResponseCode(201).setBody(SESSION))
        vm.selectTable(mesa)
        submitAndWait()
        vm.setGuests(5)
        submitAndWait()
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `reserva proxima pide confirmar y luego envia acknowledgeReservation`() {
        respond(
            conflict("TABLE_RESERVED_SOON", ""","details":{"reservationStartAt":"2026-11-02T20:00:00Z"}"""),
            MockResponse().setResponseCode(201).setBody(SESSION),
        )
        vm.selectTable(mesa)
        submitAndWait()
        assertEquals("2026-11-02T20:00:00Z", vm.state.value.opening?.reservationStartAt)
        assertFalse(posts[0].body.readUtf8().contains("acknowledgeReservation"))
        submitAndWait()
        assertTrue(posts[1].body.readUtf8().contains(""""acknowledgeReservation":true"""))
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `mesa ya asignada muestra el motivo y no confirma por su cuenta`() {
        respond(conflict("TABLE_ALREADY_ASSIGNED"))
        vm.selectTable(mesa)
        submitAndWait()
        val failure = vm.state.value.opening?.failure as ApiFailure.Server
        assertEquals("TABLE_ALREADY_ASSIGNED", failure.code)
        assertNull(vm.state.value.opening?.reservationStartAt)
        assertEquals(1, posts.size)
        vm.dismissOpen()
        assertNull(vm.state.value.opening)
    }
}
