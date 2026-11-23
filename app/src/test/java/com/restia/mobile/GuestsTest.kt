package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.orders.GuestsState
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.orders.guestsFailureText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun session(version: Int, guests: Int) =
    """{"data":{"id":"s1","status":"ACTIVE","guestCount":$guests,"version":$version,"tables":[]}}"""

private fun error(code: String) = """{"error":{"code":"$code","message":"del servidor"}}"""

private const val EMPTY_LIST = """{"data":[],"meta":{"pagination":{"page":1,"perPage":100,"total":0,"lastPage":1}}}"""

class GuestsPureTest {
    @Test
    fun `solo se guarda un numero distinto, dentro del limite y con version`() {
        val ready = GuestsState(count = 5, current = 4, version = 2, loading = false)
        assertTrue(ready.canConfirm)
        assertFalse(ready.copy(count = 4).canConfirm)
        assertFalse(ready.copy(version = null).canConfirm)
        assertFalse(ready.copy(loading = true).canConfirm)
        assertFalse(ready.copy(submitting = true).canConfirm)
        assertFalse(ready.copy(count = 0).canConfirm)
        assertFalse(ready.copy(count = 201).canConfirm)
    }

    @Test
    fun `cada falla tiene su texto`() {
        fun server(status: Int, code: String) = ApiFailure.Server(status, code, "del servidor", null)
        assertEquals(R.string.guests_conflict, guestsFailureText(server(409, "VERSION_CONFLICT")))
        assertEquals(R.string.guests_inactive, guestsFailureText(server(409, "SESSION_NOT_ACTIVE")))
        assertEquals(R.string.error_action_forbidden, guestsFailureText(server(403, "FORBIDDEN")))
        assertEquals(R.string.error_network, guestsFailureText(ApiFailure.Network))
        assertNull(guestsFailureText(server(422, "VALIDATION_FAILED")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuestsFlowTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var vm: TableViewModel
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                return routes["${request.method} ${request.requestUrl!!.encodedPath}"]?.invoke() ?: MockResponse().setBody(EMPTY_LIST)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = main.track(TableViewModel(OrderRepository(api), "b1", "s1", pollMillis = 3_600_000, sessions = TablesRepository(api)))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun patches() = requests.filter { it.first.method == "PATCH" }

    private fun openDialog() {
        vm.startGuests()
        waitFor { vm.state.value.guests?.loading == false }
    }

    @Test
    fun `la pantalla muestra los comensales de la sesion`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, 4)) }
        vm.startPolling()
        waitFor { vm.state.value.guestCount == 4 }
        vm.stopPolling()
    }

    @Test
    fun `guarda con la version leida y avisa el nuevo numero`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, 4)) }
        routes["PATCH /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(4, 6)) }
        openDialog()
        assertEquals(4, vm.state.value.guests?.count)
        vm.setGuests(6)
        vm.confirmGuests()
        waitFor { vm.state.value.guests == null }
        val (request, body) = patches().single()
        assertEquals("\"3\"", request.getHeader("If-Match"))
        assertEquals("""{"guestCount":6}""", body)
        assertEquals(6, vm.state.value.guestCount)
        assertEquals(6, vm.state.value.guestsChanged)
    }

    @Test
    fun `si otro empleado cambio la mesa relee y conserva lo elegido`() {
        var reads = 0
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(if (reads++ == 0) session(3, 4) else session(5, 3)) }
        routes["PATCH /branches/b1/table-sessions/s1"] = { MockResponse().setResponseCode(409).setBody(error("VERSION_CONFLICT")) }
        openDialog()
        vm.setGuests(6)
        vm.confirmGuests()
        waitFor { vm.state.value.guests?.failure != null }
        val guests = vm.state.value.guests!!
        assertEquals(6, guests.count)
        assertEquals(3, guests.current)
        assertEquals(5, guests.version)
        assertFalse(guests.submitting)
        assertEquals(3, vm.state.value.guestCount)
    }

    @Test
    fun `si el conflicto es porque ya quedo como se pidio se da por hecho`() {
        var reads = 0
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(if (reads++ == 0) session(3, 4) else session(4, 6)) }
        routes["PATCH /branches/b1/table-sessions/s1"] = { MockResponse().setResponseCode(409).setBody(error("VERSION_CONFLICT")) }
        openDialog()
        vm.setGuests(6)
        vm.confirmGuests()
        waitFor { vm.state.value.guests == null }
        assertEquals(6, vm.state.value.guestsChanged)
    }

    @Test
    fun `sin red el dialogo sigue abierto para reintentar`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, 4)) }
        routes["PATCH /branches/b1/table-sessions/s1"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        openDialog()
        vm.setGuests(2)
        vm.confirmGuests()
        waitFor { vm.state.value.guests?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.guests?.failure)
        assertTrue(vm.state.value.guests!!.canConfirm)
    }

    @Test
    fun `sin poder leer la sesion no envia nada y deja reintentar`() {
        var fail = true
        routes["GET /branches/b1/table-sessions/s1"] = {
            if (fail) MockResponse().setResponseCode(503).setBody(error("SERVICE_BUSY")) else MockResponse().setBody(session(3, 4))
        }
        openDialog()
        assertNull(vm.state.value.guests?.version)
        vm.confirmGuests()
        assertTrue(patches().isEmpty())
        fail = false
        vm.retryGuests()
        waitFor { vm.state.value.guests?.version == 3 }
        assertEquals(4, vm.state.value.guests?.count)
    }
}
