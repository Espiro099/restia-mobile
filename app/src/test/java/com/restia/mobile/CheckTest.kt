package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.checks.CheckRepository
import com.restia.mobile.ui.checks.CheckViewModel
import com.restia.mobile.ui.checks.PayState
import com.restia.mobile.ui.checks.ResultState
import com.restia.mobile.ui.checks.available
import com.restia.mobile.ui.checks.isPayable
import com.restia.mobile.ui.checks.parseAmount
import java.math.BigDecimal
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun mxn(amount: String) = """{"amount":"$amount","currency":"MXN"}"""

private fun checkJson(id: String = "c1", status: String = "OPEN", balance: String = "232.00", pending: String = "0.00", version: Int = 2) =
    """{"id":"$id","branchId":"b1","sessionId":"s1","number":1,"label":null,"status":"$status",
    "units":[{"unitId":"u1","orderId":"o1","productName":"Tacos","status":"DELIVERED","price":${mxn("100.00")},"tax":${mxn("16.00")}},
    {"unitId":"u2","orderId":"o1","productName":"Tacos","status":"DELIVERED","price":${mxn("100.00")},"tax":${mxn("16.00")}}],
    "subtotal":${mxn("200.00")},"taxTotal":${mxn("32.00")},"adjustmentTotal":${mxn("0.00")},"total":${mxn("232.00")},
    "paidTotal":${mxn("0.00")},"pendingTotal":${mxn(pending)},"tipTotal":${mxn("0.00")},"refundedTotal":${mxn("0.00")},
    "balance":${mxn(balance)},"businessDate":"2026-11-10","createdAt":"2026-11-10T18:00:00Z","version":$version}"""

private fun paymentJson(id: String, method: String, status: String, change: String? = null, version: Int = 1) =
    """{"id":"$id","branchId":"b1","checkId":"c1","cashShiftId":"sh1","method":"$method","status":"$status","amount":${mxn("232.00")},
    "tip":${mxn("0.00")},"change":${change?.let { mxn(it) } ?: "null"},"createdBy":"w1","createdAt":"2026-11-10T19:00:00Z","version":$version}"""

private fun pageOf(vararg rows: String) =
    """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private fun check(balance: String = "232.00", pending: String = "0.00", status: String = "OPEN") =
    ApiJson.decodeFromString(CheckDto.serializer(), checkJson(balance = balance, pending = pending, status = status))

class CheckPureTest {
    @Test
    fun `los importes aceptan hasta dos decimales con punto o coma`() {
        assertEquals(BigDecimal("12.00"), parseAmount("12"))
        assertEquals(BigDecimal("12.50"), parseAmount(" 12,5 "))
        assertNull(parseAmount("12.345"))
        assertNull(parseAmount("-1"))
        assertNull(parseAmount("doce"))
        assertNull(parseAmount(""))
    }

    @Test
    fun `solo se cobra una cuenta con saldo y en estado que admite pagos`() {
        assertTrue(isPayable(check()))
        assertFalse(isPayable(check(balance = "0.00", status = "PAID")))
        assertFalse(isPayable(check(status = "CANCELLED")))
        assertEquals(BigDecimal("132.00"), available(check(pending = "100.00")))
    }

    @Test
    fun `en efectivo lo recibido debe cubrir monto y propina`() {
        val base = PayState(check(), shiftId = "sh1", amount = "232.00", tip = "20")
        assertFalse(base.canConfirm)
        assertFalse(base.copy(tendered = "250").canConfirm)
        val ok = base.copy(tendered = "300")
        assertTrue(ok.canConfirm)
        assertEquals(BigDecimal("48.00"), ok.change)
    }

    @Test
    fun `no se cobra de mas ni sin caja`() {
        assertTrue(PayState(check(), shiftId = "sh1", amount = "300", tendered = "300").exceeds)
        assertFalse(PayState(check(), shiftId = null, amount = "100", tendered = "100").canConfirm)
        assertTrue(PayState(check(), shiftId = "sh1", method = "CARD_CREDIT", amount = "100").canConfirm)
        assertFalse(PayState(check(), shiftId = "sh1", method = "CARD_CREDIT", amount = "0").canConfirm)
    }

    @Test
    fun `confirmar un pago con tarjeta exige la referencia`() {
        val payment = ApiJson.decodeFromString(PaymentDto.serializer(), paymentJson("p1", "CARD_CREDIT", "PENDING"))
        assertFalse(ResultState(payment).canConfirm)
        assertTrue(ResultState(payment).canReject)
        assertTrue(ResultState(payment, reference = "TERM-1").canConfirm)
        assertFalse(ResultState(payment, reference = "TERM-1", last4 = "42").canConfirm)
        assertTrue(ResultState(payment, reference = "TERM-1", last4 = "4242").canConfirm)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: CheckViewModel
    private val gets = mutableMapOf<String, () -> MockResponse>()
    private val answers = ArrayDeque<MockResponse>()
    private val posts = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "GET") return gets[path]?.invoke() ?: MockResponse().setBody(pageOf())
                posts += request to request.body.readUtf8()
                return answers.removeFirst()
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = CheckViewModel(CheckRepository(api), "b1", "s1", pollMillis = 3_600_000, newKey = { "clave-${++keys}" })
        gets["/branches/b1/checks"] = { MockResponse().setBody(pageOf(checkJson())) }
        gets["/branches/b1/cash-shifts"] = { MockResponse().setBody(pageOf("""{"id":"sh1","branchId":"b1","cashRegisterId":"r1","status":"OPEN","businessDate":"2026-11-10","openedBy":"m","openedAt":"2026-11-10T12:00:00Z","version":1}""")) }
        gets["/branches/b1/cash-registers"] = { MockResponse().setBody(pageOf("""{"id":"r1","branchId":"b1","code":"C1","name":"Caja principal","status":"ACTIVE","version":1}""")) }
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

    private fun startPaying(): PayState {
        loaded()
        vm.startPay(vm.state.value.checks[0])
        waitFor { vm.state.value.paying?.shiftId != null }
        return vm.state.value.paying!!
    }

    @Test
    fun `carga las cuentas de la sesion con sus pagos`() {
        gets["/branches/b1/payments"] = { MockResponse().setBody(pageOf(paymentJson("p1", "CASH", "CONFIRMED"))) }
        loaded()
        assertEquals(1, vm.state.value.checks.size)
        assertEquals(listOf("p1"), vm.state.value.payments["c1"]?.map { it.id })
    }

    @Test
    fun `con una sola caja abierta queda elegida y propone lo que falta`() {
        val paying = startPaying()
        assertEquals("sh1", paying.shiftId)
        assertEquals("232.00", paying.amount)
        assertEquals("Caja principal", vm.state.value.shifts?.single()?.registerName)
    }

    @Test
    fun `cobra en efectivo con lo recibido y avisa el cambio`() {
        startPaying()
        vm.setTip("20")
        vm.setTendered("300")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${paymentJson("p1", "CASH", "CONFIRMED", change = "48.00")}}""")
        vm.confirmPay()
        waitFor { vm.state.value.paying == null }
        val (request, body) = posts.single()
        assertEquals("/branches/b1/payments", request.path)
        assertEquals("clave-1", request.getHeader("Idempotency-Key"))
        assertEquals(
            """{"checkId":"c1","cashShiftId":"sh1","method":"CASH","amount":{"amount":"232.00","currency":"MXN"},""" +
                """"tip":{"amount":"20.00","currency":"MXN"},"amountTendered":{"amount":"300.00","currency":"MXN"}}""",
            body,
        )
        assertEquals(R.string.pay_done_change, vm.state.value.notice?.text)
        assertEquals("48.00", vm.state.value.notice?.arg)
    }

    @Test
    fun `sin respuesta el dialogo se conserva y reintentar no cobra dos veces`() {
        startPaying()
        vm.setTendered("232")
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${paymentJson("p1", "CASH", "CONFIRMED")}}""")
        vm.confirmPay()
        waitFor { vm.state.value.paying?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.paying?.failure)
        vm.confirmPay()
        waitFor { vm.state.value.paying == null }
        assertEquals(posts[0].first.getHeader("Idempotency-Key"), posts[1].first.getHeader("Idempotency-Key"))
    }

    @Test
    fun `si el monto supera el saldo lo explica el servidor y el dialogo sigue`() {
        startPaying()
        vm.setTendered("232")
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PAYMENT_EXCEEDS_BALANCE","message":"El monto supera el saldo disponible."}}""")
        vm.confirmPay()
        waitFor { vm.state.value.paying?.failure != null }
        assertEquals("PAYMENT_EXCEEDS_BALANCE", (vm.state.value.paying?.failure as ApiFailure.Server).code)
    }

    @Test
    fun `con tarjeta queda pendiente y se registra lo que dijo la terminal`() {
        startPaying()
        vm.setMethod("CARD_CREDIT")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${paymentJson("p1", "CARD_CREDIT", "PENDING", version = 1)}}""")
        vm.confirmPay()
        waitFor { vm.state.value.recording != null }
        assertFalse(posts.single().second.contains("amountTendered"))
        vm.setBrand("VISA")
        vm.setReference(" TERM-1 ")
        vm.setLast4("42a42")
        answers += MockResponse().setBody("""{"data":${paymentJson("p1", "CARD_CREDIT", "CONFIRMED", version = 2)}}""")
        vm.recordResult("CONFIRMED")
        waitFor { vm.state.value.recording == null }
        val (request, body) = posts[1]
        assertEquals("/branches/b1/payments/p1/result", request.path)
        assertEquals("\"1\"", request.getHeader("If-Match"))
        assertNotNull(request.getHeader("Idempotency-Key"))
        assertEquals("""{"outcome":"CONFIRMED","reference":"TERM-1","card":{"brand":"VISA","last4":"4242"}}""", body)
        assertEquals(R.string.pay_done, vm.state.value.notice?.text)
    }

    @Test
    fun `rechazado no manda referencia ni tarjeta`() {
        gets["/branches/b1/payments"] = { MockResponse().setBody(pageOf(paymentJson("p1", "CARD_DEBIT", "PENDING"))) }
        loaded()
        vm.startResult(vm.state.value.payments["c1"]!!.single())
        vm.setReference("algo")
        answers += MockResponse().setBody("""{"data":${paymentJson("p1", "CARD_DEBIT", "FAILED", version = 2)}}""")
        vm.recordResult("FAILED")
        waitFor { vm.state.value.recording == null }
        assertEquals("""{"outcome":"FAILED"}""", posts.single().second)
        assertEquals(R.string.result_failed_done, vm.state.value.notice?.text)
    }

    @Test
    fun `si otro empleado ya registro el resultado se cierra el dialogo y avisa`() {
        gets["/branches/b1/payments"] = { MockResponse().setBody(pageOf(paymentJson("p1", "TRANSFER", "PENDING"))) }
        loaded()
        vm.startResult(vm.state.value.payments["c1"]!!.single())
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"VERSION_CONFLICT","message":"Cambió"}}""")
        vm.recordResult("CANCELLED")
        waitFor { vm.state.value.recording == null }
        assertEquals("VERSION_CONFLICT", (vm.state.value.actionFailure as ApiFailure.Server).code)
    }

    @Test
    fun `sin caja abierta no se puede cobrar`() {
        gets["/branches/b1/cash-shifts"] = { MockResponse().setBody(pageOf()) }
        loaded()
        vm.startPay(vm.state.value.checks[0])
        waitFor { vm.state.value.shifts != null }
        assertEquals(emptyList<Any>(), vm.state.value.shifts)
        vm.setTendered("232")
        assertFalse(vm.state.value.paying!!.canConfirm)
    }

    @Test
    fun `pedir la cuenta usa la version de la cuenta`() {
        loaded()
        answers += MockResponse().setBody("""{"data":${checkJson(status = "REQUESTED", version = 3)}}""")
        vm.requestCheck(vm.state.value.checks[0])
        waitFor { posts.isNotEmpty() && !vm.state.value.working }
        assertEquals("/branches/b1/checks/c1/request", posts.single().first.path)
        assertEquals("\"2\"", posts.single().first.getHeader("If-Match"))
        assertEquals(R.string.check_requested_done, vm.state.value.notice?.text)
    }

    @Test
    fun `cada impresion es un trabajo nuevo salvo el reintento sin respuesta`() {
        loaded()
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"id":"j1","type":"PRE_CHECK","status":"PENDING"}}""")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"id":"j2","type":"PRE_CHECK","status":"PENDING"}}""")
        vm.print(CheckViewModel.PRE_CHECK, "c1")
        waitFor { vm.state.value.actionFailure != null }
        vm.print(CheckViewModel.PRE_CHECK, "c1")
        waitFor { posts.size == 2 && !vm.state.value.working }
        vm.print(CheckViewModel.PRE_CHECK, "c1")
        waitFor { posts.size == 3 && !vm.state.value.working }
        assertEquals("""{"type":"PRE_CHECK","sourceId":"c1"}""", posts[0].second)
        assertEquals(posts[0].first.getHeader("Idempotency-Key"), posts[1].first.getHeader("Idempotency-Key"))
        assertNotEquals(posts[1].first.getHeader("Idempotency-Key"), posts[2].first.getHeader("Idempotency-Key"))
        assertEquals(R.string.print_pre_check_sent, vm.state.value.notice?.text)
    }

    @Test
    fun `sin ruta de impresion muestra el aviso del servidor`() {
        loaded()
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PRINT_ROUTE_NOT_CONFIGURED","message":"No hay una ruta activa para este documento."}}""")
        vm.print(CheckViewModel.PAYMENT_RECEIPT, "p1")
        waitFor { vm.state.value.actionFailure != null }
        assertEquals("No hay una ruta activa para este documento.", (vm.state.value.actionFailure as ApiFailure.Server).message)
        assertNull(vm.state.value.notice)
    }
}
