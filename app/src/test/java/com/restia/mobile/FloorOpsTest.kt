package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.core.network.OrderUnitDto
import com.restia.mobile.core.network.QueueUnitDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.kitchen.KitchenRepository
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.kitchen.KitchenLine
import com.restia.mobile.ui.kitchen.KitchenViewModel
import com.restia.mobile.ui.orders.SessionViewModel
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.orders.canJoin
import com.restia.mobile.ui.orders.notesEditable
import com.restia.mobile.ui.orders.sessionFailureText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun seat(id: String, number: Int, phase: String = "SEATED") = """{"tableId":"$id","number":$number,"phase":"$phase"}"""

private fun session(version: Int, vararg seats: String) =
    """{"data":{"id":"s1","status":"ACTIVE","guestCount":2,"version":$version,"tables":[${seats.joinToString()}]}}"""

private fun table(id: String, number: Int, state: String?, session: String? = null, status: String = "ACTIVE") =
    """{"id":"$id","number":$number,"capacity":4,"status":"$status","operationalState":${state?.let { "\"$it\"" } ?: "null"},"currentSessionId":${session?.let { "\"$it\"" } ?: "null"}}"""

private fun tables(vararg rows: String) =
    """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private fun error(code: String, status: Int = 409) = MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"del servidor"}}""")

class FloorOpsPureTest {
    @Test
    fun `solo se juntan mesas activas, libres y sin sesion`() {
        val free = TableDto("t1", 1, 4, status = "ACTIVE", operationalState = "LIBRE")
        assertTrue(canJoin(free))
        assertTrue(canJoin(free.copy(operationalState = "POSIBLE_OCUPACION")))
        assertFalse(canJoin(free.copy(currentSessionId = "s9", operationalState = "CONSUMIENDO")))
        assertFalse(canJoin(free.copy(operationalState = "POR_LIMPIAR")))
        assertFalse(canJoin(free.copy(status = "MAINTENANCE")))
    }

    @Test
    fun `la nota se edita solo si cocina no ha empezado nada`() {
        val item = OrderItemDto("i", "Sopa", 2, units = listOf(OrderUnitDto("a", "PENDING", 1), OrderUnitDto("b", "PENDING", 1)))
        assertTrue(notesEditable(item))
        assertFalse(notesEditable(item.copy(units = listOf(OrderUnitDto("a", "PENDING", 1), OrderUnitDto("b", "PREPARING", 2)))))
        assertFalse(notesEditable(item.copy(units = emptyList())))
    }

    @Test
    fun `cada falla de mesa tiene su texto`() {
        fun server(code: String) = ApiFailure.Server(409, code, "del servidor", null)
        assertEquals(R.string.join_taken, sessionFailureText(server("TABLE_NOT_AVAILABLE")))
        assertEquals(R.string.join_taken, sessionFailureText(server("TABLE_ALREADY_ASSIGNED")))
        assertEquals(R.string.release_last, sessionFailureText(server("SESSION_LAST_TABLE")))
        assertEquals(R.string.cancel_session_activity, sessionFailureText(server("SESSION_HAS_ACTIVITY")))
        assertEquals(R.string.error_network, sessionFailureText(ApiFailure.Network))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FloorOpsFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                return routes["${request.method} ${request.requestUrl!!.encodedPath}"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/"))
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

    private fun writes(method: String = "POST") = requests.filter { it.first.method == method }

    private fun session() = SessionViewModel(TablesRepository(api), "b1", "s1")

    @Test
    fun `juntar ofrece solo mesas libres y manda la version actual`() {
        routes["GET /branches/b1/tables"] = {
            MockResponse().setBody(tables(table("t4", 4, "CONSUMIENDO", "s1"), table("t5", 5, "LIBRE"), table("t6", 6, "POR_LIMPIAR", "s7"), table("t7", 7, "LIBRE")))
        }
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, seat("t4", 4))) }
        routes["POST /branches/b1/table-sessions/s1/tables"] = { MockResponse().setBody(session(4, seat("t4", 4), seat("t5", 5))) }
        val vm = session()
        vm.startJoin()
        waitFor { vm.state.value.joining?.candidates != null }
        assertEquals(listOf(5, 7), vm.state.value.joining!!.candidates!!.map { it.number })
        assertFalse(vm.state.value.joining!!.canConfirm)
        vm.toggleJoin("t5")
        vm.confirmJoin()
        waitFor { vm.state.value.joining == null }
        val (request, body) = writes().single()
        assertEquals("\"3\"", request.getHeader("If-Match"))
        assertEquals("""{"tableIds":["t5"]}""", body)
        assertEquals(listOf(5), vm.state.value.joined)
        assertEquals(listOf(4, 5), vm.state.value.seated.map { it.number })
    }

    @Test
    fun `si la mesa ya se ocupo relee las libres y quita la elegida`() {
        var first = true
        routes["GET /branches/b1/tables"] = {
            val five = if (first) table("t5", 5, "LIBRE") else table("t5", 5, "OCUPADA_SIN_COMANDA", "s8")
            first = false
            MockResponse().setBody(tables(five, table("t7", 7, "LIBRE")))
        }
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(3, seat("t4", 4))) }
        routes["POST /branches/b1/table-sessions/s1/tables"] = { error("TABLE_NOT_AVAILABLE") }
        val vm = session()
        vm.startJoin()
        waitFor { vm.state.value.joining?.candidates != null }
        vm.toggleJoin("t5")
        vm.confirmJoin()
        waitFor { vm.state.value.joining?.failure != null }
        val joining = vm.state.value.joining!!
        assertEquals(listOf(7), joining.candidates!!.map { it.number })
        assertTrue(joining.selected.isEmpty())
        assertFalse(joining.submitting)
    }

    @Test
    fun `separar pide al menos dos mesas y manda si queda por limpiar`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(5, seat("t4", 4), seat("t5", 5))) }
        routes["POST /branches/b1/table-sessions/s1/tables/t5/release"] = { MockResponse().setBody(session(6, seat("t4", 4))) }
        val vm = session()
        vm.load()
        waitFor { vm.state.value.seated.size == 2 }
        vm.startRelease()
        assertFalse(vm.state.value.releasing!!.canConfirm)
        vm.selectRelease("t5")
        vm.setReleaseCleaning(false)
        vm.confirmRelease()
        waitFor { vm.state.value.releasing == null }
        val (request, body) = writes().single()
        assertEquals("\"5\"", request.getHeader("If-Match"))
        assertEquals("""{"requiresCleaning":false}""", body)
        assertEquals(5, vm.state.value.released)
        // Con una sola mesa ya no se ofrece separar.
        vm.startRelease()
        assertNull(vm.state.value.releasing)
    }

    @Test
    fun `anular una apertura vuelve a las mesas y con actividad lo explica`() {
        routes["GET /branches/b1/table-sessions/s1"] = { MockResponse().setBody(session(2, seat("t4", 4))) }
        routes["POST /branches/b1/table-sessions/s1/cancel"] = { error("SESSION_HAS_ACTIVITY") }
        val vm = session()
        vm.startCancel()
        vm.setCancelReason("no")
        assertFalse(vm.state.value.cancelling!!.canConfirm)
        vm.setCancelReason("Se abrió por error")
        vm.confirmCancel()
        waitFor { vm.state.value.cancelling?.failure != null }
        assertFalse(vm.state.value.cancelled)

        routes["POST /branches/b1/table-sessions/s1/cancel"] = { MockResponse().setBody(session(3)) }
        vm.confirmCancel()
        waitFor { vm.state.value.cancelled }
        assertEquals("""{"reason":"Se abrió por error"}""", writes().last().second)
        vm.consumeCancelled()
        assertFalse(vm.state.value.cancelled)
    }

    private fun orders(notes: String?, vararg statuses: String) =
        """{"data":[{"id":"o1","clientOrderUuid":"c1","status":"OPEN","total":{"amount":"10.00","currency":"MXN"},"createdAt":"2026-11-17T18:00:00Z",
        "items":[{"id":"i1","name":"Sopa","quantity":${statuses.size},"modifiers":[],"notes":${notes?.let { "\"$it\"" } ?: "null"},"version":7,
        "units":[${statuses.mapIndexed { i, s -> """{"id":"u$i","status":"$s","version":1}""" }.joinToString()}]}]}],
        "meta":{"pagination":{"page":1,"perPage":100,"total":1,"lastPage":1}}}"""

    @Test
    fun `borrar la nota manda null explicito con la version de la partida`() {
        routes["GET /branches/b1/orders"] = { MockResponse().setBody(orders("sin sal", "PENDING", "PENDING")) }
        routes["PATCH /branches/b1/orders/o1/items/i1"] = {
            MockResponse().setBody("""{"data":{"id":"o1","clientOrderUuid":"c1","status":"OPEN","total":{"amount":"10.00","currency":"MXN"},"items":[]}}""")
        }
        val vm = TableViewModel(OrderRepository(api), "b1", "s1", pollMillis = 3_600_000)
        vm.refresh()
        waitFor { vm.state.value.loaded }
        val order = vm.state.value.orders.single()
        vm.startNotes(order, order.items.single())
        assertEquals("sin sal", vm.state.value.notes!!.text)
        assertFalse(vm.state.value.notes!!.canConfirm)
        vm.setNotes("   ")
        vm.confirmNotes()
        waitFor { vm.state.value.notesSaved != null }
        val (request, body) = writes("PATCH").single()
        assertEquals("\"7\"", request.getHeader("If-Match"))
        assertEquals("""{"notes":null}""", body)
    }

    @Test
    fun `no se edita la nota de algo que cocina ya empezo`() {
        routes["GET /branches/b1/orders"] = { MockResponse().setBody(orders(null, "PENDING", "PREPARING")) }
        val vm = TableViewModel(OrderRepository(api), "b1", "s1", pollMillis = 3_600_000)
        vm.refresh()
        waitFor { vm.state.value.loaded }
        val order = vm.state.value.orders.single()
        vm.startNotes(order, order.items.single())
        assertNull(vm.state.value.notes)
    }

    @Test
    fun `mover a otra estacion va unidad por unidad y se detiene al fallar`() {
        routes["GET /branches/b1/stations"] = {
            MockResponse().setBody("""{"data":[{"id":"st1","code":"COCINA","name":"Cocina","status":"ACTIVE"},{"id":"st2","code":"BARRA","name":"Barra","status":"ACTIVE"}],"meta":{}}""")
        }
        routes["GET /branches/b1/order-units"] = { MockResponse().setBody("""{"data":[],"meta":{"pagination":{"page":1,"perPage":100,"total":0,"lastPage":1}}}""") }
        routes["POST /branches/b1/order-units/u1/station"] = { MockResponse().setBody("{}") }
        routes["POST /branches/b1/order-units/u2/station"] = { error("ORDER_UNIT_INVALID_STATE") }
        val vm = KitchenViewModel(KitchenRepository(api), "b1", pollMillis = 3_600_000)
        vm.refresh()
        waitFor { vm.state.value.stations.size == 2 }
        val line = KitchenLine(
            "i1", "Sopa", emptyList(), null,
            listOf(QueueUnitDto("u1", "o1", "i1", 1, "PENDING", "st1", "2026-11-17T18:00:00Z", 3), QueueUnitDto("u2", "o1", "i1", 2, "PENDING", "st1", "2026-11-17T18:00:00Z", 4)),
        )
        vm.startReassign(line)
        vm.selectReassignStation("st2")
        vm.setReassignReason("La plancha está llena")
        vm.confirmReassign()
        waitFor { vm.state.value.reassigning?.failure != null }
        val moves = requests.filter { it.first.requestUrl!!.encodedPath.endsWith("/station") }
        assertEquals(listOf("\"3\"", "\"4\""), moves.map { it.first.getHeader("If-Match") })
        assertEquals("""{"stationId":"st2","reason":"La plancha está llena"}""", moves.first().second)
        assertEquals(1, vm.state.value.reassigning!!.moved)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `marcar agotado manda el valor y actualiza la carta sin esperar`() = runBlocking {
        routes["POST /branches/b1/products/p1/availability"] = { MockResponse().setBody("""{"data":{"id":"p1"}}""") }
        val menu = MenuRepository(api)
        assertTrue(menu.setAvailability("b1", "p1", available = false) is ApiResult.Success)
        assertEquals("""{"availability":"SOLD_OUT"}""", writes().single().second)
        routes["POST /branches/b1/products/p1/availability"] = { error("FORBIDDEN", 403) }
        val r = menu.setAvailability("b1", "p1", available = true)
        assertNotNull((r as ApiResult.Failure).failure)
    }
}
