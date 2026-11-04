package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.core.network.OrderUnitDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.ui.orders.MAX_UNITS_PER_BATCH
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.orders.readyUnits
import com.restia.mobile.ui.orders.unitStatusLabel
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

private fun unit(id: String, status: String, version: Int = 1) = """{"id":"$id","status":"$status","version":$version}"""

private fun order(id: String, createdAt: String, vararg units: String) =
    """{"id":"$id","clientOrderUuid":"c$id","status":"OPEN","total":{"amount":"10.00","currency":"MXN"},"createdAt":"$createdAt",
    "items":[{"id":"i$id","name":"Sopa","quantity":${units.size},"modifiers":[{"name":"Grande"}],"notes":"sin sal","units":[${units.joinToString()}]}]}"""

private fun list(vararg orders: String, lastPage: Int = 1) =
    """{"data":[${orders.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":1,"lastPage":$lastPage}}}"""

class TableViewPureTest {
    @Test
    fun `solo las unidades listas se entregan y llevan su version`() {
        val item = OrderItemDto("i", "Sopa", 3, units = listOf(OrderUnitDto("a", "READY", 4), OrderUnitDto("b", "PREPARING", 2), OrderUnitDto("c", "DELIVERED", 9)))
        assertEquals(listOf("a" to 4), readyUnits(listOf(item)).map { it.id to it.version })
    }

    @Test
    fun `cada estado del contrato tiene su texto`() {
        val expected = mapOf(
            "PENDING" to R.string.unit_pending, "PREPARING" to R.string.unit_preparing, "READY" to R.string.unit_ready,
            "DELIVERED" to R.string.unit_delivered, "CANCELLED" to R.string.unit_cancelled, "VOIDED" to R.string.unit_voided,
        )
        expected.forEach { (status, label) -> assertEquals(label, unitStatusLabel(status)) }
        assertEquals(R.string.unit_unknown, unitStatusLabel("NUEVO"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TableViewFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: OrderRepository
    private lateinit var vm: TableViewModel
    private var getAnswer: (RecordedRequest) -> MockResponse = { MockResponse().setBody(list()) }
    private val answers = ArrayDeque<MockResponse>()
    private val posts = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return getAnswer(request)
                posts += request
                bodies += request.body.readUtf8()
                return answers.removeFirst()
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        repo = OrderRepository(api)
        vm = TableViewModel(repo, "b1", "s1", pollMillis = 3_600_000, newKey = { "clave-${++keys}" })
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

    private fun deliverAllAndWait() {
        vm.deliverAll()
        waitFor { !vm.state.value.delivering }
    }

    @Test
    fun `pide las rondas de la sesion y las ordena de la mas antigua a la mas reciente`() {
        getAnswer = { MockResponse().setBody(list(order("2", "2026-11-03T11:00:00Z", unit("b", "READY")), order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        assertEquals(listOf("1", "2"), vm.state.value.orders.map { it.id })
        assertEquals("Sopa", vm.state.value.orders[0].items[0].name)
        assertEquals("/branches/b1/orders?sessionId=s1&page=1&perPage=100", server.takeRequest().path)
    }

    @Test
    fun `recorre todas las paginas y sin ETag si no cupo en una`() = runBlocking {
        getAnswer = {
            if (it.requestUrl?.queryParameter("page") == "1") {
                MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z"), lastPage = 2)).setHeader("ETag", "\"1\"")
            } else {
                MockResponse().setBody(list(order("2", "2026-11-03T11:00:00Z"), lastPage = 2))
            }
        }
        assertEquals(2, (repo.listForSession("b1", "s1") as ApiResult.Success).value.size)
        repo.listForSession("b1", "s1")
        server.takeRequest()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `sin cambios usa ETag y conserva la lista`() = runBlocking {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z"))).setHeader("ETag", "\"7\"") }
        repo.listForSession("b1", "s1")
        getAnswer = { MockResponse().setResponseCode(304) }
        assertEquals(1, (repo.listForSession("b1", "s1") as ApiResult.Success).value.size)
        server.takeRequest()
        assertEquals("\"7\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `otra sesion no reutiliza el ETag ni la lista`() = runBlocking {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z"))).setHeader("ETag", "\"7\"") }
        repo.listForSession("b1", "s1")
        repo.listForSession("b1", "s2")
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `el aviso de comanda enviada se puede retirar`() {
        vm.notifySent()
        assertEquals(true, vm.state.value.sent)
        vm.clearSent()
        assertEquals(false, vm.state.value.sent)
    }

    @Test
    fun `una falla conserva lo que se veia y avisa`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z"))) }
        loaded()
        getAnswer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        vm.refresh()
        waitFor { vm.state.value.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.failure)
        assertEquals(1, vm.state.value.orders.size)
    }

    @Test
    fun `entregar todo envia las unidades listas con su version`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY", 3), unit("b", "PREPARING", 1)))) }
        loaded()
        assertEquals(1, vm.state.value.readyCount)
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        deliverAllAndWait()
        assertEquals("""{"action":"DELIVER","units":[{"id":"a","version":3}]}""", bodies.single())
        assertEquals("clave-1", posts.single().getHeader("Idempotency-Key"))
        assertEquals("/branches/b1/order-units/transitions", posts.single().path)
        assertNull(vm.state.value.deliverFailure)
    }

    @Test
    fun `entregar una partida solo envia las suyas`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")), order("2", "2026-11-03T11:00:00Z", unit("b", "READY")))) }
        loaded()
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        vm.deliverItem(vm.state.value.orders[1].items[0])
        waitFor { !vm.state.value.delivering }
        assertTrue(bodies.single().contains("\"b\"") && !bodies.single().contains("\"a\""))
    }

    @Test
    fun `sin nada listo no envia`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        vm.deliverAll()
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `respuesta perdida reintenta con la misma clave`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")))) }
        loaded()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        deliverAllAndWait()
        assertEquals(ApiFailure.Network, vm.state.value.deliverFailure)
        deliverAllAndWait()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertNull(vm.state.value.deliverFailure)
    }

    @Test
    fun `tras exito otra entrega usa otra clave`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")))) }
        loaded()
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        deliverAllAndWait()
        deliverAllAndWait()
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `otro empleado se adelanto y la lista se actualiza`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")))) }
        loaded()
        answers.add(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"VERSION_CONFLICT","message":"m","requestId":"r"}}"""))
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "DELIVERED", 2)))) }
        deliverAllAndWait()
        waitFor { vm.state.value.readyCount == 0 }
        assertEquals("VERSION_CONFLICT", (vm.state.value.deliverFailure as ApiFailure.Server).code)
        assertEquals("DELIVERED", vm.state.value.orders[0].items[0].units[0].status)
    }

    @Test
    fun `un lote no pasa del maximo del contrato`() {
        val units = (1..60).map { unit("u$it", "READY") }.toTypedArray()
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", *units))) }
        loaded()
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}"""))
        deliverAllAndWait()
        assertEquals(MAX_UNITS_PER_BATCH, Regex("\"id\"").findAll(bodies.single()).count())
        assertFalse(bodies.single().contains("u51"))
    }

    @Test
    fun `doble toque entrega una sola vez`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")))) }
        loaded()
        answers.add(MockResponse().setBody("""{"data":[],"meta":{}}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
        vm.deliverAll()
        vm.deliverAll()
        waitFor { !vm.state.value.delivering }
        assertEquals(1, posts.size)
    }
}
