package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.core.network.OrderModifierDto
import com.restia.mobile.core.network.QueueUnitDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.kitchen.KitchenRepository
import com.restia.mobile.ui.kitchen.KitchenViewModel
import com.restia.mobile.ui.kitchen.kitchenFailureText
import com.restia.mobile.ui.kitchen.tickets
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun queued(id: String, order: String, item: String, status: String, at: String = "2026-11-10T18:00:00Z", version: Int = 1, tables: String = "[4]") =
    """{"unit":{"id":"$id","orderId":"$order","orderItemId":"$item","unitNumber":1,"status":"$status","stationId":"st1","createdAt":"$at","version":$version},
    "productName":"Tacos $item","modifiers":[{"modifierId":"m","optionId":"o","name":"Sin cebolla","priceDelta":{"amount":"0.00","currency":"MXN"}}],
    "notes":null,"tableNumbers":$tables}"""

private fun page(vararg units: String) =
    """{"data":[${units.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${units.size},"lastPage":1}}}"""

private const val STATIONS =
    """{"data":[{"id":"st2","code":"BARRA","name":"Barra","status":"ACTIVE"},{"id":"st1","code":"COCINA","name":"Cocina","status":"ACTIVE"},
    {"id":"st3","code":"VIEJA","name":"Vieja","status":"INACTIVE"}],"meta":{"pagination":{"page":1,"perPage":100,"total":3,"lastPage":1}}}"""

class KitchenPureTest {
    private fun unit(id: String, order: String, item: String, status: String, at: String) =
        KitchenUnitDto(QueueUnitDto(id, order, item, 1, status, "st1", at, 2), "Tacos $item", listOf(OrderModifierDto("Sin cebolla")), null, listOf(4))

    @Test
    fun `agrupa por comanda y partida en el orden de la cola`() {
        val result = tickets(
            listOf(
                unit("a", "o1", "i1", "PENDING", "2026-11-10T18:00:00Z"),
                unit("b", "o2", "i3", "PENDING", "2026-11-10T18:05:00Z"),
                unit("c", "o1", "i1", "PREPARING", "2026-11-10T18:00:00Z"),
                unit("d", "o1", "i2", "READY", "2026-11-10T18:00:00Z"),
            ),
        )
        assertEquals(listOf("o1", "o2"), result.map { it.orderId })
        val first = result[0]
        assertEquals(listOf("i1", "i2"), first.lines.map { it.orderItemId })
        assertEquals(listOf("a"), first.lines[0].pending.map { it.id })
        assertEquals(listOf("c"), first.lines[0].preparing.map { it.id })
        assertEquals(1, first.lines[1].ready)
        assertEquals(listOf("Sin cebolla"), first.lines[0].modifiers)
        assertEquals(listOf(4), first.tableNumbers)
    }

    @Test
    fun `cada falla de cocina tiene su texto`() {
        fun server(code: String, status: Int = 409) = ApiFailure.Server(status, code, "del servidor", null)
        assertEquals(R.string.kitchen_no_stock, kitchenFailureText(server("INVENTORY_INSUFFICIENT")))
        assertEquals(R.string.kitchen_blocked, kitchenFailureText(server("INVENTORY_BLOCKED_FOOD_SAFETY")))
        assertEquals(R.string.kitchen_conflict, kitchenFailureText(server("ORDER_UNIT_INVALID_STATE")))
        assertEquals(R.string.error_network, kitchenFailureText(ApiFailure.Network))
        assertNull(kitchenFailureText(server("VALIDATION_FAILED", 422)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: KitchenViewModel
    private var queue: (RecordedRequest) -> MockResponse = { MockResponse().setBody(page()) }
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/stations") -> MockResponse().setBody(STATIONS)
                    path.endsWith("/order-units") -> queue(request)
                    else -> answers.removeFirst()
                }
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = KitchenViewModel(KitchenRepository(api), "b1", pollMillis = 3_600_000, newKey = { "clave-${++keys}" })
    }

    @After
    fun tearDown() {
        vm.stopPolling()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun loaded() {
        vm.refresh()
        waitFor { vm.state.value.loaded || vm.state.value.failure != null }
    }

    private fun transitions() = requests.filter { it.first.method == "POST" }

    @Test
    fun `carga las estaciones activas y la cola completa`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING"))) }
        loaded()
        waitFor { vm.state.value.stations.isNotEmpty() }
        assertEquals(listOf("Barra", "Cocina"), vm.state.value.stations.map { it.name })
        assertEquals(1, vm.state.value.tickets.size)
        val path = requests.first { it.first.requestUrl!!.encodedPath.endsWith("/order-units") }.first.path!!
        assertFalse(path.contains("stationId"))
        assertFalse(path.contains("status"))
    }

    @Test
    fun `filtrar por estacion pide solo esa cola`() {
        loaded()
        vm.selectStation("st1")
        waitFor { vm.state.value.loaded && vm.state.value.stationId == "st1" }
        val last = requests.last { it.first.requestUrl!!.encodedPath.endsWith("/order-units") }.first
        assertEquals("st1", last.requestUrl!!.queryParameter("stationId"))
    }

    @Test
    fun `empezar envia START con las unidades pendientes y su version`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING", version = 3), queued("b", "o1", "i1", "PREPARING", version = 5))) }
        loaded()
        answers += MockResponse().setBody("""{"data":[]}""")
        vm.start(vm.state.value.tickets[0].lines[0])
        waitFor { transitions().isNotEmpty() && !vm.state.value.working }
        val (request, body) = transitions().single()
        assertEquals("/branches/b1/order-units/transitions", request.path)
        assertEquals("clave-1", request.getHeader("Idempotency-Key"))
        assertEquals("""{"action":"START","units":[{"id":"a","version":3}]}""", body)
        assertNull(vm.state.value.actionFailure)
    }

    @Test
    fun `listo envia READY con lo que esta en preparacion`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING"), queued("b", "o1", "i1", "PREPARING", version = 5))) }
        loaded()
        answers += MockResponse().setBody("""{"data":[]}""")
        vm.ready(vm.state.value.tickets[0].lines[0])
        waitFor { transitions().isNotEmpty() && !vm.state.value.working }
        assertEquals("""{"action":"READY","units":[{"id":"b","version":5}]}""", transitions().single().second)
    }

    @Test
    fun `sin respuesta el reintento usa la misma clave`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING"))) }
        loaded()
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        answers += MockResponse().setBody("""{"data":[]}""")
        val line = vm.state.value.tickets[0].lines[0]
        vm.start(line)
        waitFor { vm.state.value.actionFailure != null }
        assertEquals(ApiFailure.Network, vm.state.value.actionFailure)
        vm.start(line)
        waitFor { transitions().size == 2 && !vm.state.value.working }
        assertEquals(transitions()[0].first.getHeader("Idempotency-Key"), transitions()[1].first.getHeader("Idempotency-Key"))
    }

    @Test
    fun `un rechazo del servidor avisa y la siguiente vez usa otra clave`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING"))) }
        loaded()
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"INVENTORY_INSUFFICIENT","message":"No hay"}}""")
        answers += MockResponse().setBody("""{"data":[]}""")
        val line = vm.state.value.tickets[0].lines[0]
        vm.start(line)
        waitFor { vm.state.value.actionFailure != null }
        assertEquals("INVENTORY_INSUFFICIENT", (vm.state.value.actionFailure as ApiFailure.Server).code)
        vm.start(line)
        waitFor { transitions().size == 2 && !vm.state.value.working }
        assertEquals("clave-2", transitions()[1].first.getHeader("Idempotency-Key"))
    }

    @Test
    fun `una falla de red conserva la cola que se veia`() {
        queue = { MockResponse().setBody(page(queued("a", "o1", "i1", "PENDING"))) }
        loaded()
        queue = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        vm.refresh()
        waitFor { vm.state.value.failure != null }
        assertEquals(1, vm.state.value.units.size)
    }
}
