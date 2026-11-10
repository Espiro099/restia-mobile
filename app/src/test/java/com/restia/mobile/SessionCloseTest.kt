package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.orders.closeFailureText
import com.restia.mobile.ui.tables.TablesViewModel
import com.restia.mobile.ui.tables.needsCleaning
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

private fun session(version: Int, vararg tables: String) =
    """{"data":{"id":"s1","status":"ACTIVE","guestCount":2,"version":$version,"tables":[${tables.joinToString()}]}}"""

private fun seat(id: String, number: Int, phase: String, membership: Int? = null) =
    """{"tableId":"$id","number":$number,"phase":"$phase","assignedAt":"2026-11-10T10:00:00Z","cleaningMembershipVersion":${membership ?: "null"}}"""

private fun error(code: String) = """{"error":{"code":"$code","message":"del servidor"}}"""

private const val EMPTY_LIST = """{"data":[],"meta":{"pagination":{"page":1,"perPage":100,"total":0,"lastPage":1}}}"""

class SessionClosePureTest {
    @Test
    fun `solo una mesa activa en limpieza y con sesion se confirma`() {
        val base = TableDto("t1", 4, 4, status = "ACTIVE", operationalState = "POR_LIMPIAR", currentSessionId = "s1")
        assertTrue(needsCleaning(base))
        assertFalse(needsCleaning(base.copy(currentSessionId = null)))
        assertFalse(needsCleaning(base.copy(operationalState = "CONSUMIENDO")))
        assertFalse(needsCleaning(base.copy(status = "MAINTENANCE")))
    }

    @Test
    fun `cada falla del cierre tiene su texto`() {
        fun server(status: Int, code: String) = ApiFailure.Server(status, code, "del servidor", null)
        assertEquals(R.string.close_pending_units, closeFailureText(server(409, "SESSION_HAS_PENDING_UNITS")))
        assertEquals(R.string.close_open_checks, closeFailureText(server(409, "SESSION_HAS_OPEN_CHECKS")))
        assertEquals(R.string.close_conflict, closeFailureText(server(409, "VERSION_CONFLICT")))
        assertEquals(R.string.error_network, closeFailureText(ApiFailure.Network))
        assertNull(closeFailureText(server(422, "VALIDATION_FAILED")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCloseFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var tables: TablesRepository
    private lateinit var orders: OrderRepository
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return routes["${request.method} $path"]?.invoke() ?: MockResponse().setBody(EMPTY_LIST)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        tables = TablesRepository(api)
        orders = OrderRepository(api)
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

    private fun posts() = requests.filter { it.first.method == "POST" }

    private fun table() = TableViewModel(orders, "b1", "s1", pollMillis = 3_600_000, sessions = tables)

    @Test
    fun `cierra con la version actual de la sesion y vuelve a las mesas`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, seat("t1", 4, "SEATED"))) }
        routes["POST /branches/b1/table-sessions/s1/close"] = { MockResponse().setBody(session(4, seat("t1", 4, "CLEANING", 1))) }
        val vm = table()
        vm.startClose()
        vm.confirmClose()
        waitFor { vm.state.value.closed }
        assertNull(vm.state.value.closing)
        assertEquals("\"3\"", posts().single().first.getHeader("If-Match"))
        vm.consumeClosed()
        assertFalse(vm.state.value.closed)
    }

    @Test
    fun `con cuentas sin pagar el dialogo sigue abierto y lo explica`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3)) }
        routes["POST /branches/b1/table-sessions/s1/close"] = { MockResponse().setResponseCode(409).setBody(error("SESSION_HAS_OPEN_CHECKS")) }
        val vm = table()
        vm.startClose()
        vm.confirmClose()
        waitFor { vm.state.value.closing?.failure != null }
        assertEquals("SESSION_HAS_OPEN_CHECKS", (vm.state.value.closing?.failure as ApiFailure.Server).code)
        assertFalse(vm.state.value.closing!!.submitting)
        assertFalse(vm.state.value.closed)
    }

    @Test
    fun `si otro empleado ya la cerro se da por cerrada`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3)) }
        routes["POST /branches/b1/table-sessions/s1/close"] = { MockResponse().setResponseCode(409).setBody(error("SESSION_NOT_ACTIVE")) }
        val vm = table()
        vm.startClose()
        vm.confirmClose()
        waitFor { vm.state.value.closed }
    }

    @Test
    fun `sin poder leer la sesion no intenta cerrar`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setResponseCode(503).setBody(error("SERVICE_BUSY")) }
        val vm = table()
        vm.startClose()
        vm.confirmClose()
        waitFor { vm.state.value.closing?.failure != null }
        assertTrue(posts().isEmpty())
    }

    private fun floor(): TablesViewModel = TablesViewModel(tables, "b1", pollMillis = 3_600_000)

    private val dirty = TableDto("t1", 4, 4, status = "ACTIVE", operationalState = "POR_LIMPIAR", currentSessionId = "s1")

    @Test
    fun `confirma la limpieza con la version de membresia de esa mesa`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(5, seat("t2", 5, "CLEANING", 2), seat("t1", 4, "CLEANING", 3))) }
        routes["POST /branches/b1/table-sessions/s1/tables/t1/cleaning-confirmation"] = { MockResponse().setBody(session(6, seat("t2", 5, "CLEANING", 2))) }
        val vm = floor()
        vm.selectCleaning(dirty)
        vm.confirmCleaning()
        waitFor { vm.state.value.cleaned == 4 }
        assertNull(vm.state.value.cleaning)
        assertEquals("""{"membershipVersion":3}""", posts().single().second)
    }

    @Test
    fun `si ya no esta en limpieza cierra el dialogo sin confirmar`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(6, seat("t2", 5, "CLEANING", 2))) }
        val vm = floor()
        vm.selectCleaning(dirty)
        vm.confirmCleaning()
        waitFor { vm.state.value.cleaning == null }
        assertNull(vm.state.value.cleaned)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun `sin red el dialogo sigue abierto para reintentar`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(5, seat("t1", 4, "CLEANING", 3))) }
        routes["POST /branches/b1/table-sessions/s1/tables/t1/cleaning-confirmation"] =
            { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val vm = floor()
        vm.selectCleaning(dirty)
        vm.confirmCleaning()
        waitFor { vm.state.value.cleaning?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.cleaning?.failure)
    }

    @Test
    fun `una mesa que no esta por limpiar no abre el dialogo`() {
        val vm = floor()
        vm.selectCleaning(dirty.copy(operationalState = "CONSUMIENDO"))
        assertNull(vm.state.value.cleaning)
    }
}
