package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.printing.PrintingRepository
import com.restia.mobile.ui.printing.PrintAction
import com.restia.mobile.ui.printing.PrintActionKind
import com.restia.mobile.ui.printing.PrintJobsViewModel
import com.restia.mobile.ui.printing.PrintPermissions
import com.restia.mobile.ui.printing.PrintTab
import com.restia.mobile.ui.printing.canCancel
import com.restia.mobile.ui.printing.canReprint
import com.restia.mobile.ui.printing.canResolve
import com.restia.mobile.ui.printing.canRetry
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

private fun job(id: String, status: String, type: String = "KITCHEN_TICKET", version: Int = 1, createdAt: String = "2026-11-16T18:00:00Z", error: String? = null) =
    """{"id":"$id","branchId":"b1","type":"$type","status":"$status","priority":"HIGH","printerId":"pr1","attempts":3,""" +
        (if (error != null) """"lastError":{"code":"PAPER_OUT","message":"$error"},""" else "") +
        """"createdAt":"$createdAt","version":$version}"""

private fun jobs(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":50,"total":${rows.size},"lastPage":1}}}"""

private fun dto(json: String) = ApiJson.decodeFromString(PrintJobDto.serializer(), json)

class PrintJobsPureTest {
    @Test
    fun `cada accion solo en los estados que acepta el backend`() {
        val failed = dto(job("j", "FAILED"))
        val unknown = dto(job("j", "UNKNOWN"))
        val printed = dto(job("j", "PRINTED"))
        val pending = dto(job("j", "PENDING"))
        assertTrue(canRetry(failed))
        assertFalse(canRetry(unknown))
        assertTrue(canResolve(unknown))
        assertFalse(canResolve(failed))
        assertTrue(canReprint(printed) && canReprint(unknown))
        assertFalse(canReprint(failed))
        assertTrue(canCancel(pending) && canCancel(failed) && canCancel(unknown))
        assertFalse(canCancel(printed))
    }

    @Test
    fun `resolver exige decir si salio y todas piden motivo`() {
        val unknown = dto(job("j", "UNKNOWN"))
        assertFalse(PrintAction(unknown, PrintActionKind.RESOLVE, reason = "Revisé la impresora").canSubmit)
        assertTrue(PrintAction(unknown, PrintActionKind.RESOLVE, printed = false, reason = "Revisé la impresora").canSubmit)
        assertFalse(PrintAction(unknown, PrintActionKind.REPRINT, reason = "no").canSubmit)
        assertTrue(PrintAction(unknown, PrintActionKind.CANCEL, reason = "Ya se avisó a cocina").canSubmit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrintJobsFlowTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var failed = jobs(job("j1", "FAILED", error = "Sin papel", createdAt = "2026-11-16T18:00:00Z"))
    private var unknown = jobs(job("j2", "UNKNOWN", type = "PRE_CHECK", version = 2, createdAt = "2026-11-16T18:05:00Z"))
    private var recent = jobs(job("j3", "PRINTED", type = "PAYMENT_RECEIPT"), job("j1", "FAILED"))
    private var keys = 0
    /** Retraso de la lista de recientes, para simular una carga lenta. */
    private var recentDelayMs = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val url = request.requestUrl!!
                if (request.method != "GET") return answers.removeFirst()
                val delay = if (url.encodedPath == "/branches/b1/print-jobs" && url.queryParameter("status") == null) recentDelayMs else 0L
                return MockResponse().setBodyDelay(delay, TimeUnit.MILLISECONDS).setBody(
                    when (url.encodedPath) {
                        "/branches/b1/printers" -> """{"data":[{"id":"pr1","branchId":"b1","agentId":"a1","code":"COCINA","name":"Cocina caliente","connection":"NETWORK","paperWidth":80,"status":"ACTIVE","version":1}],"meta":{}}"""
                        "/branches/b1/print-jobs" -> when (url.queryParameter("status")) {
                            "FAILED" -> failed
                            "UNKNOWN" -> unknown
                            null -> recent
                            else -> jobs()
                        }
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
    }

    /** Copia tomada con el bloqueo: el servidor simulado agrega peticiones desde otro hilo. */
    private fun sent() = synchronized(requests) { requests.toList() }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun posts(path: String) = sent().filter { it.first.method == "POST" && it.first.requestUrl!!.encodedPath == path }

    private fun loaded(can: PrintPermissions = PrintPermissions(resolve = true, reprint = true)): PrintJobsViewModel {
        val vm = main.track(PrintJobsViewModel(PrintingRepository(api), "b1", can, newKey = { "clave-${++keys}" }))
        vm.refresh()
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `por atender junta los fallidos y sin confirmar, del mas reciente al mas antiguo`() {
        val vm = loaded()
        assertEquals(listOf("j2", "j1"), vm.state.value.jobs.map { it.id })
        assertEquals("Cocina caliente", vm.state.value.printers["pr1"])
        assertEquals("Sin papel", vm.state.value.jobs.first { it.id == "j1" }.lastError!!.message)
        vm.selectTab(PrintTab.RECENT)
        waitFor { vm.state.value.loaded && vm.state.value.tab == PrintTab.RECENT }
        assertEquals(listOf("j3", "j1"), vm.state.value.jobs.map { it.id })
        assertNull(sent().last { it.first.requestUrl!!.encodedPath == "/branches/b1/print-jobs" }.first.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun `reintentar manda If-Match y recarga`() {
        val vm = loaded()
        failed = jobs()
        answers += MockResponse().setBody("""{"data":${job("j1", "PENDING", version = 2)}}""")
        vm.retry(vm.state.value.jobs.first { it.id == "j1" })
        waitFor { vm.state.value.retrying == null && vm.state.value.jobs.none { it.id == "j1" } }
        val retry = posts("/branches/b1/print-jobs/j1/retry").single()
        assertEquals("\"1\"", retry.first.getHeader("If-Match"))
        assertEquals("{}", retry.second)
        assertEquals(R.string.print_retry_done, vm.state.value.notice!!.text)
    }

    @Test
    fun `resolver un sin confirmar dice si salio`() {
        val vm = loaded()
        vm.start(vm.state.value.jobs.first { it.id == "j2" }, PrintActionKind.RESOLVE)
        vm.edit { it.copy(printed = false, reason = "No salió nada en la impresora") }
        unknown = jobs()
        answers += MockResponse().setBody("""{"data":${job("j2", "FAILED", type = "PRE_CHECK", version = 3)}}""")
        vm.submit()
        waitFor { vm.state.value.action == null }
        val resolve = posts("/branches/b1/print-jobs/j2/resolve").single()
        assertEquals("\"2\"", resolve.first.getHeader("If-Match"))
        assertEquals("""{"outcome":"NOT_PRINTED","reason":"No salió nada en la impresora"}""", resolve.second)
        assertEquals(R.string.print_resolved_not_printed, vm.state.value.notice!!.text)
    }

    @Test
    fun `reimprimir sin respuesta reintenta con la misma clave`() {
        val vm = loaded()
        vm.start(vm.state.value.jobs.first { it.id == "j2" }, PrintActionKind.REPRINT)
        vm.edit { it.copy(reason = "El cliente la perdió") }
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        vm.submit()
        waitFor { vm.state.value.action?.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.action!!.failure)
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${job("j4", "PENDING", type = "PRE_CHECK")}}""")
        vm.submit()
        waitFor { vm.state.value.action == null }
        val sent = posts("/branches/b1/print-jobs/j2/reprint")
        assertEquals(2, sent.size)
        assertEquals(sent[0].first.getHeader("Idempotency-Key"), sent[1].first.getHeader("Idempotency-Key"))
        assertEquals("""{"reason":"El cliente la perdió"}""", sent[1].second)
    }

    @Test
    fun `un conflicto recarga y conserva el formulario`() {
        val vm = loaded()
        vm.start(vm.state.value.jobs.first { it.id == "j1" }, PrintActionKind.CANCEL)
        vm.edit { it.copy(reason = "Ya se avisó a cocina") }
        failed = jobs(job("j1", "FAILED", version = 4))
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"VERSION_CONFLICT","message":"Cambió."}}""")
        vm.submit()
        waitFor { vm.state.value.action?.failure != null && vm.state.value.jobs.first { it.id == "j1" }.version == 4 }
        assertEquals("\"1\"", posts("/branches/b1/print-jobs/j1/cancel").single().first.getHeader("If-Match"))
        assertEquals("Ya se avisó a cocina", vm.state.value.action!!.reason)
    }

    @Test
    fun `sin printing resolve no hay acciones`() {
        val vm = loaded(PrintPermissions(resolve = false, reprint = false))
        vm.retry(vm.state.value.jobs.first { it.id == "j1" })
        vm.start(vm.state.value.jobs.first { it.id == "j2" }, PrintActionKind.RESOLVE)
        vm.start(vm.state.value.jobs.first { it.id == "j2" }, PrintActionKind.REPRINT)
        assertNull(vm.state.value.action)
        assertTrue(sent().none { it.first.method == "POST" })
    }

    @Test
    fun `una recarga que llega durante otra no se pierde`() {
        val vm = loaded()
        vm.selectTab(PrintTab.RECENT)
        waitFor { vm.state.value.loaded && vm.state.value.tab == PrintTab.RECENT && !vm.state.value.loading }
        // Una carga lenta en curso (como el sondeo) trae la lista de antes...
        recentDelayMs = 600
        vm.refresh()
        waitFor { vm.state.value.loading }
        // ...y mientras tanto se reimprime algo: esa recarga debe repetirse al terminar.
        recentDelayMs = 0
        recent = jobs(job("j9", "PENDING", type = "PRE_CHECK", createdAt = "2026-11-16T18:39:00Z"), job("j3", "PRINTED", type = "PAYMENT_RECEIPT"))
        vm.refresh()
        waitFor { !vm.state.value.loading && vm.state.value.jobs.firstOrNull()?.id == "j9" }
    }
}
