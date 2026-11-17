package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.checks.CheckRepository
import com.restia.mobile.ui.checks.CheckAction
import com.restia.mobile.ui.checks.CheckActionKind
import com.restia.mobile.ui.checks.CheckViewModel
import com.restia.mobile.ui.checks.canAdjust
import com.restia.mobile.ui.checks.canCancelCheck
import com.restia.mobile.ui.checks.canMoveFrom
import com.restia.mobile.ui.checks.canReopen
import com.restia.mobile.ui.checks.courtesyCandidates
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

private fun mxn(amount: String) = """{"amount":"$amount","currency":"MXN"}"""

private fun unit(id: String, name: String = "Tacos", status: String = "DELIVERED", adjustment: String? = null) =
    """{"unitId":"$id","orderId":"o1","productName":"$name","status":"$status","price":${mxn("100.00")},"tax":${mxn("13.79")}""" +
        (if (adjustment != null) ""","adjustment":{"id":"$adjustment","type":"COURTESY","amount":${mxn("100.00")},"reason":"Cumpleaños","createdAt":"2026-11-16T19:00:00Z"}""" else "") + "}"

private fun checkJson(id: String, number: Int, status: String = "OPEN", units: List<String> = emptyList(), paid: String = "0.00", pending: String = "0.00", version: Int = 1) =
    """{"id":"$id","sessionId":"s1","number":$number,"status":"$status","units":[${units.joinToString()}],
    "subtotal":${mxn("0.00")},"taxTotal":${mxn("0.00")},"adjustmentTotal":${mxn("0.00")},"total":${mxn("0.00")},
    "paidTotal":${mxn(paid)},"pendingTotal":${mxn(pending)},"tipTotal":${mxn("0.00")},"balance":${mxn("0.00")},"version":$version}"""

private fun page(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private fun dto(json: String) = ApiJson.decodeFromString(CheckDto.serializer(), json)

class CheckEditsPureTest {
    @Test
    fun `que se puede hacer segun el estado y los pagos`() {
        val open = dto(checkJson("c1", 1, units = listOf(unit("u1"), unit("u2", status = "VOIDED"))))
        assertTrue(canMoveFrom(open))
        assertTrue(canAdjust(open))
        assertEquals(listOf("u1"), courtesyCandidates(open).map { it.unitId })
        assertFalse(canCancelCheck(open))
        assertFalse(canReopen(open))

        val paying = dto(checkJson("c1", 1, status = "REQUESTED", units = listOf(unit("u1")), pending = "50.00"))
        assertFalse(canMoveFrom(paying))
        assertFalse(canAdjust(paying))
        assertFalse(canReopen(paying))
        assertTrue(canReopen(dto(checkJson("c1", 1, status = "REQUESTED", units = listOf(unit("u1"))))))

        // Reembolsada: se pueden mover sus unidades aunque tuvo pagos (P-8).
        assertTrue(canMoveFrom(dto(checkJson("c1", 1, status = "REFUNDED", units = listOf(unit("u1")), paid = "100.00"))))
        // Solo anuladas o canceladas: está vacía y se puede cancelar.
        val empty = dto(checkJson("c2", 2, units = listOf(unit("u3", status = "CANCELLED"))))
        assertTrue(canCancelCheck(empty))
        assertFalse(canMoveFrom(empty))
    }

    @Test
    fun `el token sirve para el motivo y el recurso con que se pidio`() {
        val check = dto(checkJson("c1", 1, units = listOf(unit("u1"), unit("u2"))))
        val needs = CheckAction(CheckActionKind.COURTESY, check, needsApproval = true, reason = "Cumpleaños")
        assertFalse(needs.canSubmit)
        val withUnit = needs.copy(unit = check.units[0])
        assertEquals(listOf("u1"), withUnit.resourceIds)
        assertFalse(withUnit.canSubmit)
        val ready = withUnit.copy(approver = com.restia.mobile.ui.checks.Approver("gerente@demo.mx", "secreta"))
        assertTrue(ready.canSubmit)
        assertFalse(ready.toString().contains("secreta"))
        val granted = ready.copy(approval = com.restia.mobile.ui.checks.GrantedApproval("tok", listOf("u1"), "Cumpleaños", Long.MAX_VALUE))
        assertTrue(granted.approved)
        assertFalse(granted.copy(reason = "Otro motivo").approved)
        assertFalse(granted.copy(unit = check.units[1]).approved)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckEditsFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var checks = page(checkJson("c1", 1, units = listOf(unit("u1"), unit("u2", name = "Agua"), unit("u3", name = "Flan", adjustment = "a1")), version = 3))
    private var keys = 0
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                if (request.method == "GET") {
                    return MockResponse().setBody(if (request.requestUrl!!.encodedPath == "/branches/b1/checks") checks else page())
                }
                return answers.removeFirst()
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

    private fun loaded(reopen: Boolean = false, adjust: Boolean = false): CheckViewModel {
        val vm = CheckViewModel(
            CheckRepository(api), "b1", "s1", pollMillis = 3_600_000, newKey = { "clave-${++keys}" },
            canReopenDirectly = reopen, canAdjustDirectly = adjust, now = { clock },
        )
        vm.refresh()
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `dividir a una cuenta nueva la crea una sola vez aunque mover falle`() {
        val vm = loaded()
        vm.startMove(vm.state.value.checks[0])
        assertNull(vm.state.value.moving!!.targetId)
        vm.toggleUnit("u2")
        vm.setNewLabel("Ana")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${checkJson("c2", 2)}}""")
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        vm.confirmMove()
        waitFor { vm.state.value.moving?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.moving!!.failure)
        assertEquals("c2", vm.state.value.moving!!.created!!.id)
        val created = posts("/branches/b1/checks").single()
        assertEquals("""{"sessionId":"s1","label":"Ana"}""", created.second)
        assertTrue(created.first.getHeader("Idempotency-Key")!!.isNotBlank())

        // El reintento no crea otra cuenta: mueve a la que ya existe.
        answers += MockResponse().setBody("""{"data":${checkJson("c1", 1, units = listOf(unit("u1")), version = 4)}}""")
        vm.confirmMove()
        waitFor { vm.state.value.moving == null }
        assertEquals(1, posts("/branches/b1/checks").size)
        val moves = posts("/branches/b1/checks/c1/units/move")
        assertEquals(2, moves.size)
        assertEquals("\"3\"", moves.last().first.getHeader("If-Match"))
        assertEquals("""{"unitIds":["u2"],"targetCheckId":"c2","targetCheckVersion":1}""", moves.last().second)
        assertEquals(R.string.move_done, vm.state.value.notice!!.text)
    }

    @Test
    fun `mover a otra cuenta abierta usa su version`() {
        checks = page(checkJson("c1", 1, units = listOf(unit("u1"), unit("u2")), version = 3), checkJson("c2", 2, version = 7))
        val vm = loaded()
        vm.startMove(vm.state.value.checks.first { it.id == "c1" })
        assertEquals("c2", vm.state.value.moving!!.targetId)
        vm.toggleUnit("u1")
        answers += MockResponse().setBody("""{"data":${checkJson("c1", 1, units = listOf(unit("u2")), version = 4)}}""")
        vm.confirmMove()
        waitFor { vm.state.value.moving == null }
        assertTrue(posts("/branches/b1/checks").isEmpty())
        assertEquals("""{"unitIds":["u1"],"targetCheckId":"c2","targetCheckVersion":7}""", posts("/branches/b1/checks/c1/units/move").single().second)
    }

    @Test
    fun `reabrir sin permiso pide el token del gerente para la cuenta y el motivo`() {
        checks = page(checkJson("c1", 1, status = "REQUESTED", units = listOf(unit("u1")), version = 5))
        val vm = loaded()
        vm.startAction(CheckActionKind.REOPEN, vm.state.value.checks[0])
        assertTrue(vm.state.value.action!!.showsApprover)
        vm.setActionReason("Pidieron postre")
        vm.setApproverEmail("gerente@demo.mx")
        vm.setApproverPassword("clave-del-gerente")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"approvalToken":"tok-1","expiresIn":120}}""")
        answers += MockResponse().setBody("""{"data":${checkJson("c1", 1, units = listOf(unit("u1")), version = 6)}}""")
        vm.confirmAction()
        waitFor { vm.state.value.action == null }
        assertEquals(
            """{"approverEmail":"gerente@demo.mx","approverPassword":"clave-del-gerente","action":"CHECK_REOPEN","resourceIds":["c1"],"reason":"Pidieron postre"}""",
            posts("/branches/b1/approvals").single().second,
        )
        val reopen = posts("/branches/b1/checks/c1/reopen").single()
        assertEquals("tok-1", reopen.first.getHeader("X-Approval-Token"))
        assertEquals("\"5\"", reopen.first.getHeader("If-Match"))
        assertEquals("""{"reason":"Pidieron postre"}""", reopen.second)
        assertEquals(R.string.check_reopened, vm.state.value.notice!!.text)
    }

    @Test
    fun `con permiso directo la cortesia no pide token`() {
        val vm = loaded(adjust = true)
        vm.startAction(CheckActionKind.COURTESY, vm.state.value.checks[0])
        assertFalse(vm.state.value.action!!.showsApprover)
        vm.chooseCourtesyUnit(vm.state.value.checks[0].units.first { it.unitId == "u2" })
        vm.setActionReason("Cumpleaños")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${checkJson("c1", 1, version = 4)}}""")
        vm.confirmAction()
        waitFor { vm.state.value.action == null }
        assertTrue(posts("/branches/b1/approvals").isEmpty())
        val adj = posts("/branches/b1/checks/c1/adjustments").single()
        assertNull(adj.first.getHeader("X-Approval-Token"))
        assertEquals("""{"unitId":"u2","type":"COURTESY","reason":"Cumpleaños"}""", adj.second)
    }

    @Test
    fun `un token rechazado se descarta y se pide otro`() {
        val vm = loaded()
        val flan = vm.state.value.checks[0].units.first { it.unitId == "u3" }
        vm.startAction(CheckActionKind.REVERT, vm.state.value.checks[0], flan)
        vm.setActionReason("Se equivocó de platillo")
        vm.setApproverEmail("gerente@demo.mx")
        vm.setApproverPassword("clave")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"approvalToken":"tok-1","expiresIn":120}}""")
        answers += MockResponse().setResponseCode(403).setBody("""{"error":{"code":"APPROVAL_INVALID","message":"La autorización ya no es válida."}}""")
        vm.confirmAction()
        waitFor { vm.state.value.action?.failure != null }
        assertEquals("""["a1"]""", Regex(""""resourceIds":(\[[^\]]*])""").find(posts("/branches/b1/approvals").single().second)!!.groupValues[1])
        assertEquals("tok-1", posts("/branches/b1/checks/c1/adjustments/a1/revert").single().first.getHeader("X-Approval-Token"))
        val action = vm.state.value.action!!
        assertNull(action.approval)
        assertTrue(action.showsApprover)
        // La contraseña ya se borró al recibir el token: el gerente la vuelve a escribir.
        assertEquals("", action.approver.password)
    }

    @Test
    fun `cancelar una cuenta vacia`() {
        checks = page(checkJson("c1", 1, units = listOf(unit("u1"))), checkJson("c2", 2, units = listOf(unit("u9", status = "VOIDED")), version = 2))
        val vm = loaded()
        vm.startAction(CheckActionKind.CANCEL, vm.state.value.checks.first { it.id == "c1" })
        assertNull(vm.state.value.action)
        vm.startAction(CheckActionKind.CANCEL, vm.state.value.checks.first { it.id == "c2" })
        vm.setActionReason("Se abrió por error")
        answers += MockResponse().setBody("""{"data":${checkJson("c2", 2, status = "CANCELLED", version = 3)}}""")
        vm.confirmAction()
        waitFor { vm.state.value.action == null }
        val cancel = posts("/branches/b1/checks/c2/cancel").single()
        assertEquals("\"2\"", cancel.first.getHeader("If-Match"))
        assertEquals("""{"reason":"Se abrió por error"}""", cancel.second)
    }
}
