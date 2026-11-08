package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.core.network.OrderUnitDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.ui.orders.MAX_UNITS_PER_BATCH
import com.restia.mobile.ui.orders.REASON_MAX
import com.restia.mobile.ui.orders.actionFailureText
import com.restia.mobile.ui.orders.isValidReason
import com.restia.mobile.ui.orders.pendingUnits
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.orders.readyUnits
import com.restia.mobile.ui.orders.unitStatusLabel
import com.restia.mobile.ui.orders.voidableUnits
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
            "PENDING" to R.plurals.unit_pending, "PREPARING" to R.plurals.unit_preparing, "READY" to R.plurals.unit_ready,
            "DELIVERED" to R.plurals.unit_delivered, "CANCELLED" to R.plurals.unit_cancelled, "VOIDED" to R.plurals.unit_voided,
        )
        expected.forEach { (status, label) -> assertEquals(label, unitStatusLabel(status)) }
        assertEquals(R.plurals.unit_unknown, unitStatusLabel("NUEVO"))
    }

    @Test
    fun `solo las unidades pendientes se pueden cancelar`() {
        val item = OrderItemDto("i", "Sopa", 3, units = listOf(OrderUnitDto("a", "PENDING", 2), OrderUnitDto("b", "PREPARING", 2), OrderUnitDto("c", "PENDING", 5)))
        assertEquals(listOf("a" to 2, "c" to 5), pendingUnits(item).map { it.id to it.version })
    }

    @Test
    fun `el motivo cumple los limites del contrato sin contar espacios`() {
        assertFalse(isValidReason("  ab  "))
        assertTrue(isValidReason("abc"))
        assertTrue(isValidReason("x".repeat(REASON_MAX)))
        assertFalse(isValidReason("x".repeat(REASON_MAX + 1)))
    }

    @Test
    fun `cada falla de cancelacion tiene su texto`() {
        fun server(status: Int, code: String?) = ApiFailure.Server(status, code, "del servidor", null)
        assertEquals(R.string.cancel_invalid_state, actionFailureText(server(409, "ORDER_UNIT_INVALID_STATE")))
        assertEquals(R.string.cancel_paid, actionFailureText(server(409, "ORDER_UNIT_PAID")))
        assertEquals(R.string.table_deliver_conflict, actionFailureText(server(409, "VERSION_CONFLICT")))
        assertEquals(R.string.error_action_forbidden, actionFailureText(server(403, "FORBIDDEN")))
        assertEquals(R.string.error_server, actionFailureText(server(503, "SERVICE_BUSY")))
        assertEquals(R.string.error_network, actionFailureText(ApiFailure.Network))
        assertNull(actionFailureText(server(422, "VALIDATION_FAILED")))
    }

    @Test
    fun `al anular los mismos codigos se explican distinto`() {
        fun server(status: Int, code: String?) = ApiFailure.Server(status, code, "del servidor", null)
        assertEquals(R.string.void_invalid_state, actionFailureText(server(409, "ORDER_UNIT_INVALID_STATE"), "VOID"))
        assertEquals(R.string.void_paid, actionFailureText(server(409, "ORDER_UNIT_PAID"), "VOID"))
        assertEquals(R.string.void_denied, actionFailureText(server(403, "APPROVAL_DENIED"), "VOID"))
        assertEquals(R.string.void_approval_invalid, actionFailureText(server(409, "APPROVAL_INVALID"), "VOID"))
        assertEquals(R.string.cancel_invalid_state, actionFailureText(server(409, "ORDER_UNIT_INVALID_STATE"), "CANCEL"))
    }

    @Test
    fun `solo lo empezado o listo se puede anular`() {
        val item = OrderItemDto("i", "Sopa", 4, units = listOf(OrderUnitDto("a", "PENDING", 1), OrderUnitDto("b", "PREPARING", 2), OrderUnitDto("c", "READY", 3), OrderUnitDto("d", "DELIVERED", 4)))
        assertEquals(listOf("b" to 2, "c" to 3), voidableUnits(item).map { it.id to it.version })
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
        assertNull(vm.state.value.actionFailure)
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
        assertEquals(ApiFailure.Network, vm.state.value.actionFailure)
        deliverAllAndWait()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertNull(vm.state.value.actionFailure)
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
        assertEquals("VERSION_CONFLICT", (vm.state.value.actionFailure as ApiFailure.Server).code)
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

    private val ok = """{"data":[],"meta":{}}"""

    private fun cancelAndWait() {
        vm.confirmCancel()
        waitFor { vm.state.value.cancelling?.submitting != true }
    }

    private fun firstItem() = vm.state.value.orders[0].items[0]

    @Test
    fun `cancelar envia solo las pendientes con su version y el motivo`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING", 3), unit("b", "PREPARING"), unit("c", "PENDING", 7)))) }
        loaded()
        answers.add(MockResponse().setBody(ok))
        vm.startCancel(firstItem())
        assertEquals(2, vm.state.value.cancelling!!.count)
        vm.setCancelReason("  El cliente cambió de opinión  ")
        cancelAndWait()
        val body = ApiJson.parseToJsonElement(bodies.single()).toString()
        assertEquals("""{"action":"CANCEL","units":[{"id":"a","version":3},{"id":"c","version":7}],"reason":"El cliente cambió de opinión"}""", body)
        assertEquals("/branches/b1/order-units/transitions", posts.single().path)
        assertNull(vm.state.value.cancelling)
        assertEquals(2, vm.state.value.cancelled)
    }

    @Test
    fun `se puede cancelar solo una parte de lo pendiente`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING"), unit("c", "PENDING")))) }
        loaded()
        answers.add(MockResponse().setBody(ok))
        vm.startCancel(firstItem())
        vm.setCancelCount(0)
        assertEquals(1, vm.state.value.cancelling!!.count)
        vm.setCancelCount(9)
        assertEquals(2, vm.state.value.cancelling!!.count)
        vm.setCancelCount(1)
        vm.setCancelReason("Error de captura")
        cancelAndWait()
        assertTrue(bodies.single().contains("\"a\"") && !bodies.single().contains("\"c\""))
        assertEquals(1, vm.state.value.cancelled)
    }

    @Test
    fun `sin motivo valido no envia`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        vm.startCancel(firstItem())
        vm.setCancelReason("no")
        vm.confirmCancel()
        assertTrue(posts.isEmpty())
        assertFalse(vm.state.value.cancelling!!.canConfirm)
    }

    @Test
    fun `sin pendientes no abre la cancelacion`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "READY")))) }
        loaded()
        vm.startCancel(firstItem())
        assertNull(vm.state.value.cancelling)
    }

    @Test
    fun `respuesta perdida deja el dialogo y reintenta con la misma clave`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setBody(ok))
        vm.startCancel(firstItem())
        vm.setCancelReason("Error de captura")
        cancelAndWait()
        assertEquals(ApiFailure.Network, vm.state.value.cancelling!!.failure)
        cancelAndWait()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertNull(vm.state.value.cancelling)
    }

    @Test
    fun `cambiar el motivo tras perder la respuesta usa otra clave`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setBody(ok))
        vm.startCancel(firstItem())
        vm.setCancelReason("Error de captura")
        cancelAndWait()
        vm.setCancelReason("El cliente se fue")
        cancelAndWait()
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `si cocina ya empezo cierra el dialogo avisa y actualiza`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        answers.add(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"ORDER_UNIT_INVALID_STATE","message":"m","requestId":"r"}}"""))
        vm.startCancel(firstItem())
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PREPARING", 2)))) }
        vm.setCancelReason("Error de captura")
        cancelAndWait()
        assertNull(vm.state.value.cancelling)
        assertEquals("ORDER_UNIT_INVALID_STATE", (vm.state.value.actionFailure as ApiFailure.Server).code)
        waitFor { vm.state.value.orders[0].items[0].units[0].status == "PREPARING" }
    }

    @Test
    fun `mientras se cancela no se puede entregar`() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING"), unit("b", "READY")))) }
        loaded()
        answers.add(MockResponse().setBody(ok).setBodyDelay(300, TimeUnit.MILLISECONDS))
        vm.startCancel(firstItem())
        vm.setCancelReason("Error de captura")
        vm.confirmCancel()
        vm.deliverAll()
        waitFor { vm.state.value.cancelling == null }
        assertEquals(1, posts.size)
        assertTrue(bodies.single().contains("CANCEL"))
    }

    // --- Anular (VOID) con autorización de un gerente (D-37) ---

    private var clock = 0L

    private fun voidVm(direct: Boolean) {
        vm.stopPolling()
        vm = TableViewModel(repo, "b1", "s1", canVoidDirectly = direct, pollMillis = 3_600_000, newKey = { "clave-${++keys}" }, now = { clock })
    }

    private fun approvalOk(expiresIn: Int = 120) = MockResponse().setResponseCode(201).setBody("""{"data":{"approvalToken":"token-1","expiresIn":$expiresIn},"meta":{}}""")

    private fun voidAndWait() {
        vm.confirmVoid()
        waitFor { vm.state.value.voiding?.submitting != true }
    }

    private fun startedTable() {
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING"), unit("b", "PREPARING", 2), unit("c", "READY", 5)))) }
        loaded()
    }

    private fun fillApprover(password: String = "clave-del-gerente") {
        vm.setVoidReason("Se cayó el plato")
        vm.setApproverEmail("gerente@ejemplo.mx")
        vm.setApproverPassword(password)
    }

    @Test
    fun `sin permiso pide la autorizacion y anula con el token`() {
        voidVm(direct = false)
        startedTable()
        answers.add(approvalOk())
        answers.add(MockResponse().setBody(ok))
        vm.startVoid(firstItem())
        assertEquals(2, vm.state.value.voiding!!.count)
        assertTrue(vm.state.value.voiding!!.showsApprover)
        vm.setVoidReason("Se cayó el plato")
        assertFalse("sin gerente no deja confirmar", vm.state.value.voiding!!.canConfirm)
        fillApprover()
        voidAndWait()

        assertEquals("/branches/b1/approvals", posts[0].path)
        assertEquals(
            """{"approverEmail":"gerente@ejemplo.mx","approverPassword":"clave-del-gerente","action":"ORDER_UNIT_VOID","resourceIds":["b","c"],"reason":"Se cayó el plato"}""",
            ApiJson.parseToJsonElement(bodies[0]).toString(),
        )
        assertNull(posts[0].getHeader("Idempotency-Key"))
        assertEquals("/branches/b1/order-units/transitions", posts[1].path)
        assertEquals("token-1", posts[1].getHeader("X-Approval-Token"))
        assertEquals("""{"action":"VOID","units":[{"id":"b","version":2},{"id":"c","version":5}],"reason":"Se cayó el plato"}""", ApiJson.parseToJsonElement(bodies[1]).toString())
        assertNull(vm.state.value.voiding)
        assertEquals(2, vm.state.value.voided)
    }

    @Test
    fun `con permiso directo anula sin autorizacion ni token`() {
        voidVm(direct = true)
        startedTable()
        answers.add(MockResponse().setBody(ok))
        vm.startVoid(firstItem())
        assertFalse(vm.state.value.voiding!!.showsApprover)
        vm.setVoidCount(1)
        vm.setVoidReason("Se cayó el plato")
        voidAndWait()
        assertEquals(1, posts.size)
        assertNull(posts[0].getHeader("X-Approval-Token"))
        assertTrue(bodies[0].contains("\"b\"") && !bodies[0].contains("\"c\""))
        assertEquals(1, vm.state.value.voided)
    }

    @Test
    fun `credenciales rechazadas dejan el dialogo y borran la contrasena`() {
        voidVm(direct = false)
        startedTable()
        answers.add(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"APPROVAL_DENIED","message":"m","requestId":"r"}}"""))
        vm.startVoid(firstItem())
        fillApprover("mala")
        voidAndWait()
        val state = vm.state.value.voiding!!
        assertEquals("APPROVAL_DENIED", (state.failure as ApiFailure.Server).code)
        assertEquals("", state.approverPassword)
        assertEquals("gerente@ejemplo.mx", state.approverEmail)
        assertEquals(1, posts.size)
        assertFalse(state.canConfirm)
    }

    @Test
    fun `sin red al pedir autorizacion conserva los datos para reintentar`() {
        voidVm(direct = false)
        startedTable()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.startVoid(firstItem())
        fillApprover()
        voidAndWait()
        assertEquals(ApiFailure.Network, vm.state.value.voiding!!.failure)
        assertEquals("clave-del-gerente", vm.state.value.voiding!!.approverPassword)
        assertTrue(vm.state.value.voiding!!.canConfirm)
    }

    @Test
    fun `respuesta perdida reintenta con la misma clave y el mismo token`() {
        voidVm(direct = false)
        startedTable()
        answers.add(approvalOk())
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setBody(ok))
        vm.startVoid(firstItem())
        fillApprover()
        voidAndWait()
        val state = vm.state.value.voiding!!
        assertEquals(ApiFailure.Network, state.failure)
        assertTrue("ya autorizado, sin pedir otra vez al gerente", state.approved && !state.showsApprover)
        assertEquals("", state.approverPassword)
        voidAndWait()
        assertEquals(3, posts.size)
        assertEquals(posts[1].getHeader("Idempotency-Key"), posts[2].getHeader("Idempotency-Key"))
        assertEquals("token-1", posts[2].getHeader("X-Approval-Token"))
        assertEquals(2, vm.state.value.voided)
    }

    @Test
    fun `token rechazado por el servidor pide otra autorizacion sin cerrar`() {
        voidVm(direct = false)
        startedTable()
        answers.add(approvalOk())
        answers.add(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"APPROVAL_INVALID","message":"m","requestId":"r"}}"""))
        vm.startVoid(firstItem())
        fillApprover()
        voidAndWait()
        val state = vm.state.value.voiding!!
        assertEquals("APPROVAL_INVALID", (state.failure as ApiFailure.Server).code)
        assertNull(state.approval)
        assertTrue(state.showsApprover)
        assertFalse("hace falta la contraseña otra vez", state.canConfirm)
    }

    @Test
    fun `token vencido en el telefono no se envia`() {
        voidVm(direct = false)
        startedTable()
        answers.add(approvalOk(expiresIn = 120))
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.startVoid(firstItem())
        fillApprover()
        voidAndWait()
        clock += 121_000
        voidAndWait()
        assertEquals(2, posts.size)
        val state = vm.state.value.voiding!!
        assertEquals("APPROVAL_INVALID", (state.failure as ApiFailure.Server).code)
        assertTrue(state.showsApprover)
    }

    @Test
    fun `cambiar la cantidad tras autorizar exige otra autorizacion`() {
        voidVm(direct = false)
        startedTable()
        answers.add(approvalOk())
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        vm.startVoid(firstItem())
        fillApprover()
        voidAndWait()
        assertTrue(vm.state.value.voiding!!.approved)
        vm.setVoidCount(1)
        assertTrue(vm.state.value.voiding!!.showsApprover)
        vm.setVoidCount(2)
        assertTrue("volver a lo autorizado sirve", vm.state.value.voiding!!.approved)
    }

    @Test
    fun `si algo cambio de estado cierra el dialogo y lo explica como anulacion`() {
        voidVm(direct = true)
        startedTable()
        answers.add(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"ORDER_UNIT_INVALID_STATE","message":"m","requestId":"r"}}"""))
        vm.startVoid(firstItem())
        vm.setVoidReason("Se cayó el plato")
        voidAndWait()
        assertNull(vm.state.value.voiding)
        assertEquals("VOID", vm.state.value.failedAction)
        assertEquals("ORDER_UNIT_INVALID_STATE", (vm.state.value.actionFailure as ApiFailure.Server).code)
    }

    @Test
    fun `sin nada empezado no abre la anulacion`() {
        voidVm(direct = true)
        getAnswer = { MockResponse().setBody(list(order("1", "2026-11-03T10:00:00Z", unit("a", "PENDING")))) }
        loaded()
        vm.startVoid(firstItem())
        assertNull(vm.state.value.voiding)
    }

    @Test
    fun `la contrasena no aparece en el estado impreso`() {
        voidVm(direct = false)
        startedTable()
        vm.startVoid(firstItem())
        fillApprover("secreta-123")
        assertFalse(vm.state.value.toString().contains("secreta-123"))
    }
}
