package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.cash.CashRepository
import com.restia.mobile.ui.cash.CashPermissions
import com.restia.mobile.ui.cash.CashViewModel
import com.restia.mobile.ui.cash.CloseForm
import com.restia.mobile.ui.cash.MoveForm
import com.restia.mobile.ui.cash.OpenForm
import com.restia.mobile.ui.cash.ResolveForm
import com.restia.mobile.ui.cash.ResolveKind
import com.restia.mobile.ui.cash.closingDifference
import com.restia.mobile.core.network.CashRegisterDto
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun mxn(amount: String) = """{"amount":"$amount","currency":"MXN"}"""

private fun register(id: String, name: String, status: String = "ACTIVE") =
    """{"id":"$id","branchId":"b1","code":"${id.uppercase()}","name":"$name","status":"$status","version":1}"""

private fun shift(id: String, register: String, status: String = "OPEN", version: Int = 1, extra: String = "") =
    """{"id":"$id","branchId":"b1","cashRegisterId":"$register","status":"$status","businessDate":"2026-11-16","openedBy":"u",""" +
        """"openedAt":"2026-11-16T15:00:00Z","openingAmount":${mxn("500.00")},"version":$version$extra}"""

private fun payment(id: String, status: String, method: String = "CARD_CREDIT", refundId: String? = null, version: Int = 1, shift: String = "s1") =
    """{"id":"$id","checkId":"c1","cashShiftId":"$shift","method":"$method","status":"$status","amount":${mxn("207.00")},"tip":${mxn("20.00")},""" +
        (if (refundId != null) """"refundId":"$refundId",""" else "") + """"createdAt":"2026-11-16T16:10:00Z","version":$version}"""

private fun refund(id: String, status: String, shift: String = "s1", version: Int = 1) =
    """{"id":"$id","paymentId":"p1","checkId":"c1","cashShiftId":"$shift","status":"$status","amount":${mxn("207.00")},"tip":${mxn("20.00")},""" +
        """"reason":"Platillo frío","createdAt":"2026-11-16T16:20:00Z","version":$version}"""

private fun page(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private fun summary(expected: String, status: String = "OPEN", pending: Int = 0) =
    """{"data":{"cashShiftId":"s1","status":"$status","openingAmount":${mxn("500.00")},""" +
        """"deposits":${mxn("100.00")},"withdrawals":${mxn("0.00")},"expectedCash":${mxn(expected)},""" +
        """"byMethod":[{"method":"CASH","sales":${mxn("600.00")},"tips":${mxn("50.00")},"refunds":${mxn("0.00")},"tipsRefunded":${mxn("0.00")}}],""" +
        """"unresolved":{"pendingPayments":$pending,"unknownPayments":0,"pendingRefunds":0,"unknownRefunds":0},"frozen":false}}"""

class CashPureTest {
    private val caja = CashRegisterDto("r1", "C1", "Caja 1", "ACTIVE")

    @Test
    fun `la diferencia es lo contado menos lo esperado`() {
        assertEquals(BigDecimal("-50.00"), closingDifference(BigDecimal("1200.00"), BigDecimal("1250.00")))
        assertEquals(BigDecimal("0.00"), closingDifference(BigDecimal("1250.00"), BigDecimal("1250.00")))
    }

    @Test
    fun `el corte pide motivo solo con diferencia y no deja mas de lo contado`() {
        val cuadra = CloseForm(BigDecimal("1250.00"), counted = "1250", left = "500")
        assertFalse(cuadra.needsReason)
        assertTrue(cuadra.canSubmit)
        val falta = cuadra.copy(counted = "1200")
        assertTrue(falta.needsReason)
        assertFalse(falta.canSubmit)
        assertTrue(falta.copy(reason = "Faltó cambio").canSubmit)
        val deMas = cuadra.copy(left = "1300")
        assertTrue(deMas.leftTooHigh)
        assertFalse(deMas.canSubmit)
        assertFalse(cuadra.copy(counted = "12.345").canSubmit)
    }

    @Test
    fun `los movimientos exigen importe positivo y motivo`() {
        assertFalse(MoveForm("DEPOSIT", amount = "0", reason = "Cambio").canSubmit)
        assertFalse(MoveForm("DEPOSIT", amount = "100", reason = "ab").canSubmit)
        assertTrue(MoveForm("WITHDRAWAL", amount = "100,50", reason = "Pago a proveedor").canSubmit)
    }

    @Test
    fun `resolver exige referencia al confirmar y motivo salvo en el resultado`() {
        val conciliar = ResolveForm(ResolveKind.RECONCILE_PAYMENT, reason = "Aparece en el cierre")
        assertFalse(conciliar.canSubmit)
        assertTrue(conciliar.copy(reference = "AUT-123").canSubmit)
        assertTrue(conciliar.copy(outcome = "FAILED").canSubmit)
        assertFalse(conciliar.copy(outcome = "FAILED", reason = "no").canSubmit)
        assertTrue(ResolveForm(ResolveKind.REFUND_RESULT, reference = "DEV-9").canSubmit)
        assertTrue(ResolveForm(ResolveKind.REFUND_RESULT, outcome = "FAILED").canSubmit)
        assertFalse(ResolveForm(ResolveKind.REFUND).canSubmit)
        assertTrue(ResolveForm(ResolveKind.REFUND, reason = "Platillo frío").canSubmit)
    }

    @Test
    fun `abrir pide motivo solo cuando el backend lo exige`() {
        assertTrue(OpenForm(caja, amount = "500").canSubmit)
        val conDiferencia = OpenForm(caja, amount = "500", difference = "-50.00")
        assertTrue(conDiferencia.needsReason)
        assertFalse(conDiferencia.canSubmit)
        assertTrue(conDiferencia.copy(reason = "Se retiró para depósito").canSubmit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CashFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var registers = page(register("r2", "Caja barra"), register("r1", "Caja 1"), register("r3", "Caja vieja", status = "INACTIVE"))
    private var openShifts = page(shift("s1", "r1"))
    private var closingShifts = page()
    private var current = shift("s1", "r1")
    private var currentSummary = summary("1150.00")
    private var movements = page()
    private var shiftPayments = page()
    private var refundsPending = page()
    private var refundsUnknown = page()
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val url = request.requestUrl!!
                if (request.method != "GET") return answers.removeFirst()
                return MockResponse().setBody(
                    when (url.encodedPath) {
                        "/branches/b1/cash-registers" -> registers
                        "/branches/b1/cash-shifts" -> if (url.queryParameter("status") == "CLOSING") closingShifts else openShifts
                        "/branches/b1/cash-shifts/s1" -> """{"data":$current}"""
                        "/branches/b1/cash-shifts/s1/summary" -> currentSummary
                        "/branches/b1/cash-shifts/s1/movements" -> movements
                        "/branches/b1/payments" -> if (url.queryParameter("cashShiftId") == "s1") shiftPayments else page()
                        "/branches/b1/refunds" -> if (url.queryParameter("status") == "PENDING") refundsPending else refundsUnknown
                        else -> return MockResponse().setResponseCode(404)
                    },
                )
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

    /** Copia tomada con el bloqueo: el servidor simulado agrega peticiones desde otro hilo. */
    private fun sent() = synchronized(requests) { requests.toList() }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun posts(path: String) = sent().filter { it.first.method == "POST" && it.first.requestUrl!!.encodedPath == path }

    private fun loaded(can: CashPermissions = CashPermissions(open = true, move = true, close = true)): CashViewModel {
        val vm = CashViewModel(CashRepository(api), "b1", can, newKey = { "clave-${++keys}" })
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `lista las cajas activas con su turno`() {
        val vm = loaded()
        val rows = vm.state.value.registers
        assertEquals(listOf("Caja 1", "Caja barra"), rows.map { it.register.name })
        assertEquals("s1", rows.first { it.register.id == "r1" }.shift!!.id)
        assertNull(rows.first { it.register.id == "r2" }.shift)
        val statuses = sent().filter { it.first.requestUrl!!.encodedPath == "/branches/b1/cash-shifts" }.map { it.first.requestUrl!!.queryParameter("status") }
        assertEquals(listOf("OPEN", "CLOSING"), statuses)
    }

    @Test
    fun `abrir con diferencia pide el motivo y lo reintenta sin repetir la clave`() {
        val vm = loaded()
        vm.startOpen(vm.state.value.registers.first { it.register.id == "r2" }.register)
        vm.editOpen { it.copy(amount = "450") }
        answers += MockResponse().setResponseCode(409)
            .setBody("""{"error":{"code":"CASH_SHIFT_REASON_REQUIRED","message":"Falta el motivo.","details":{"openingDifference":"-50.00"}}}""")
        vm.submitOpen()
        waitFor { vm.state.value.opening?.needsReason == true }
        // La primera vez no es un error: se explica la diferencia y se pide el motivo.
        assertNull(vm.state.value.opening!!.failure)
        assertEquals("-50.00", vm.state.value.opening!!.difference)
        assertFalse(vm.state.value.opening!!.canSubmit)

        vm.editOpen { it.copy(reason = "Se retiró para depósito") }
        // Sin respuesta del servidor: el reintento manda la misma clave.
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        vm.submitOpen()
        waitFor { vm.state.value.opening?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.opening!!.failure)
        openShifts = page(shift("s1", "r1"), shift("s2", "r2"))
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${shift("s2", "r2")}}""")
        vm.submitOpen()
        waitFor { vm.state.value.opening == null }

        val sent = posts("/branches/b1/cash-shifts")
        assertEquals("""{"cashRegisterId":"r2","openingAmount":{"amount":"450.00","currency":"MXN"}}""", sent[0].second)
        assertEquals(
            """{"cashRegisterId":"r2","openingAmount":{"amount":"450.00","currency":"MXN"},"openingDifferenceReason":"Se retiró para depósito"}""",
            sent[2].second,
        )
        val sentKeys = sent.map { it.first.getHeader("Idempotency-Key") }
        assertNotEquals(sentKeys[0], sentKeys[1])
        assertEquals(sentKeys[1], sentKeys[2])
        waitFor { vm.state.value.registers.first { it.register.id == "r2" }.shift?.id == "s2" }
    }

    @Test
    fun `sin permiso de abrir no hay formulario`() {
        val vm = loaded(CashPermissions(open = false, move = false, close = false))
        vm.startOpen(vm.state.value.registers.first().register)
        assertNull(vm.state.value.opening)
    }

    @Test
    fun `deposito y corte completo con diferencia`() {
        val vm = loaded()
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.summary != null }
        assertEquals("1150.00", vm.state.value.detail!!.summary!!.expectedCash.amount)

        vm.startMove("DEPOSIT")
        vm.editMove { it.copy(amount = "100", reason = "Cambio para la noche") }
        movements = page("""{"id":"m1","branchId":"b1","cashShiftId":"s1","type":"DEPOSIT","amount":${mxn("100.00")},"reason":"Cambio para la noche","createdBy":"u","createdAt":"2026-11-16T16:00:00Z"}""")
        currentSummary = summary("1250.00")
        answers += MockResponse().setResponseCode(201).setBody(
            """{"data":{"id":"m1","branchId":"b1","cashShiftId":"s1","type":"DEPOSIT","amount":${mxn("100.00")},"reason":"Cambio para la noche","createdBy":"u","createdAt":"2026-11-16T16:00:00Z"}}""",
        )
        vm.submitMove()
        waitFor { vm.state.value.moving == null && vm.state.value.detail?.movements?.size == 1 }
        val move = posts("/branches/b1/cash-shifts/s1/movements").single()
        assertEquals("""{"type":"DEPOSIT","amount":{"amount":"100.00","currency":"MXN"},"reason":"Cambio para la noche"}""", move.second)
        assertTrue(move.first.getHeader("Idempotency-Key")!!.isNotBlank())

        // Paso 1: iniciar el corte frena operaciones nuevas.
        vm.askStartClosing()
        assertTrue(vm.state.value.confirmingStart)
        current = shift("s1", "r1", status = "CLOSING", version = 2)
        currentSummary = summary("1250.00", status = "CLOSING")
        answers += MockResponse().setBody("""{"data":$current}""")
        vm.confirmStartClosing()
        waitFor { vm.state.value.detail?.shift?.status == "CLOSING" && vm.state.value.detail?.summary?.status == "CLOSING" }
        val start = posts("/branches/b1/cash-shifts/s1/start-closing").single()
        assertEquals("\"1\"", start.first.getHeader("If-Match"))
        assertEquals("", start.second)

        // Paso 2: contar y cerrar; faltan 50, así que pide el motivo.
        vm.startClose()
        vm.editClose { it.copy(counted = "1200", left = "500") }
        assertTrue(vm.state.value.closing!!.needsReason)
        vm.submitClose()
        assertTrue(posts("/branches/b1/cash-shifts/s1/close").isEmpty())
        vm.editClose { it.copy(reason = "Faltó cambio") }
        answers += MockResponse().setBody(
            """{"data":${shift("s1", "r1", status = "CLOSED", version = 3, extra = ""","difference":${mxn("-50.00")}""")}}""",
        )
        vm.submitClose()
        waitFor { vm.state.value.detail == null }
        val close = posts("/branches/b1/cash-shifts/s1/close").single()
        assertEquals("\"2\"", close.first.getHeader("If-Match"))
        assertEquals(
            """{"countedCash":{"amount":"1200.00","currency":"MXN"},"cashLeftInDrawer":{"amount":"500.00","currency":"MXN"},"differenceReason":"Faltó cambio"}""",
            close.second,
        )
        assertEquals(R.string.cash_closed_short, vm.state.value.notice!!.text)
        assertEquals("$50.00 MXN", vm.state.value.notice!!.arg)
    }

    @Test
    fun `con pagos sin resolver el corte queda bloqueado y se puede cancelar`() {
        current = shift("s1", "r1", status = "CLOSING", version = 4)
        openShifts = page()
        closingShifts = page(current)
        currentSummary = summary("1250.00", status = "CLOSING", pending = 1)
        val vm = loaded()
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.summary != null }
        assertEquals(1, vm.state.value.detail!!.unresolved)

        vm.startCancel()
        vm.editCancel("Falta conciliar una tarjeta")
        current = shift("s1", "r1", version = 5)
        answers += MockResponse().setBody("""{"data":$current}""")
        vm.submitCancel()
        waitFor { vm.state.value.cancelling == null && vm.state.value.detail?.shift?.status == "OPEN" }
        val cancel = posts("/branches/b1/cash-shifts/s1/cancel-closing").single()
        assertEquals("\"4\"", cancel.first.getHeader("If-Match"))
        assertEquals("""{"reason":"Falta conciliar una tarjeta"}""", cancel.second)
    }

    private val all = CashPermissions(
        open = true, move = true, close = true, readPayments = true, reconcilePayments = true,
        readRefunds = true, refund = true, refundResult = true, reconcileRefunds = true,
    )

    @Test
    fun `concilia un pago sin confirmar y reembolsa uno confirmado`() {
        shiftPayments = page(payment("p1", "CONFIRMED", method = "CASH"), payment("p2", "UNKNOWN", version = 3))
        currentSummary = summary("1150.00", pending = 1)
        val vm = loaded(all)
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.payments?.size == 2 }
        assertEquals("s1", sent().first { it.first.requestUrl!!.encodedPath == "/branches/b1/payments" }.first.requestUrl!!.queryParameter("cashShiftId"))

        vm.startReconcilePayment(vm.state.value.detail!!.payments.first { it.id == "p2" })
        vm.editResolve { it.copy(reference = " AUT-778 ", reason = "Aparece en el cierre de la terminal") }
        shiftPayments = page(payment("p1", "CONFIRMED", method = "CASH"), payment("p2", "CONFIRMED", version = 4))
        answers += MockResponse().setBody("""{"data":${payment("p2", "CONFIRMED", version = 4)}}""")
        vm.submitResolve()
        waitFor { vm.state.value.resolving == null && vm.state.value.detail?.payments?.all { it.status == "CONFIRMED" } == true }
        val reconcile = posts("/branches/b1/payments/p2/reconcile").single()
        assertEquals("\"3\"", reconcile.first.getHeader("If-Match"))
        assertTrue(reconcile.first.getHeader("Idempotency-Key")!!.isNotBlank())
        assertEquals("""{"outcome":"CONFIRMED","reference":"AUT-778","reason":"Aparece en el cierre de la terminal"}""", reconcile.second)
        assertEquals(R.string.cash_resolved_confirmed, vm.state.value.notice!!.text)

        // Reembolsar el pago en efectivo: va al turno que se ve.
        vm.startRefund(vm.state.value.detail!!.payments.first { it.id == "p1" })
        vm.editResolve { it.copy(reason = "Platillo frío") }
        shiftPayments = page(payment("p1", "CONFIRMED", method = "CASH", refundId = "f1"), payment("p2", "CONFIRMED", version = 4))
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${refund("f1", "CONFIRMED")}}""")
        vm.submitResolve()
        waitFor { vm.state.value.resolving == null && vm.state.value.detail?.payments?.first { it.id == "p1" }?.refundId == "f1" }
        assertEquals("""{"cashShiftId":"s1","reason":"Platillo frío"}""", posts("/branches/b1/payments/p1/refunds").single().second)
        assertEquals(R.string.cash_refund_done, vm.state.value.notice!!.text)
        // Ya reembolsado, no se ofrece otra vez.
        vm.startRefund(vm.state.value.detail!!.payments.first { it.id == "p1" })
        assertNull(vm.state.value.resolving)
    }

    @Test
    fun `mientras recarga el turno no se puede reembolsar`() {
        shiftPayments = page(payment("p1", "CONFIRMED", method = "CASH"))
        val vm = loaded(all)
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.payments?.size == 1 && vm.state.value.detail?.loading == false }
        vm.loadDetail()
        assertTrue(vm.state.value.detail!!.loading)
        // Lo que se ve puede estar por cambiar (por ejemplo, justo después de otro reembolso).
        vm.startRefund(vm.state.value.detail!!.payments.single())
        assertNull(vm.state.value.resolving)
        waitFor { vm.state.value.detail?.loading == false }
        vm.startRefund(vm.state.value.detail!!.payments.single())
        assertEquals(ResolveKind.REFUND, vm.state.value.resolving!!.kind)
    }

    @Test
    fun `resuelve los reembolsos del turno y deja fuera los de otros`() {
        refundsPending = page(refund("f1", "PENDING"), refund("f9", "PENDING", shift = "s7"))
        refundsUnknown = page(refund("f2", "UNKNOWN", version = 2))
        val vm = loaded(all)
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.refunds?.size == 2 }
        assertEquals(listOf("f1", "f2"), vm.state.value.detail!!.refunds.map { it.id })

        vm.startRefundResolution(vm.state.value.detail!!.refunds.first { it.id == "f1" })
        assertEquals(ResolveKind.REFUND_RESULT, vm.state.value.resolving!!.kind)
        vm.editResolve { it.copy(outcome = "FAILED") }
        refundsPending = page(refund("f9", "PENDING", shift = "s7"))
        answers += MockResponse().setBody("""{"data":${refund("f1", "FAILED", version = 2)}}""")
        vm.submitResolve()
        waitFor { vm.state.value.resolving == null && vm.state.value.detail?.refunds?.map { it.id } == listOf("f2") }
        val result = posts("/branches/b1/refunds/f1/result").single()
        assertEquals("\"1\"", result.first.getHeader("If-Match"))
        assertEquals("""{"outcome":"FAILED"}""", result.second)

        vm.startRefundResolution(vm.state.value.detail!!.refunds.single())
        assertEquals(ResolveKind.RECONCILE_REFUND, vm.state.value.resolving!!.kind)
        vm.editResolve { it.copy(reference = "DEV-55", reason = "El banco confirmó la devolución") }
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"VERSION_CONFLICT","message":"Cambió."}}""")
        refundsUnknown = page(refund("f2", "UNKNOWN", version = 3))
        vm.submitResolve()
        waitFor { vm.state.value.resolving?.failure != null && vm.state.value.detail?.refunds?.single()?.version == 3 }
        assertEquals("""{"outcome":"CONFIRMED","reference":"DEV-55","reason":"El banco confirmó la devolución"}""", posts("/branches/b1/refunds/f2/reconcile").single().second)
    }

    @Test
    fun `sin permisos de pagos no los pide ni ofrece acciones`() {
        val vm = loaded()
        vm.view(vm.state.value.registers.first { it.register.id == "r1" })
        waitFor { vm.state.value.detail?.loading == false }
        assertTrue(sent().none { it.first.requestUrl!!.encodedPath in setOf("/branches/b1/payments", "/branches/b1/refunds") })
    }
}
