package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ReservationDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.reservations.ReservationsRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.reservations.ReservationsViewModel
import com.restia.mobile.ui.reservations.canMarkNoShow
import com.restia.mobile.ui.reservations.fieldMessage
import com.restia.mobile.ui.reservations.isValidPhone
import com.restia.mobile.ui.reservations.isoInstant
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

private val ZONE: ZoneId = ZoneId.of("America/Mexico_City")

/** 2026-11-20 10:00 en Ciudad de México. */
private val NOW: Instant = Instant.parse("2026-11-20T16:00:00Z")

private fun reservation(id: String, status: String, start: String = "2026-11-20T20:00:00Z", end: String = "2026-11-20T22:00:00Z", planned: String = "", version: Int = 1) =
    """{"id":"$id","branchId":"b1","status":"$status","startAt":"$start","endAt":"$end","partySize":4,
    "customer":{"customerId":null,"name":"Ana López","phone":"+523312345678"},"source":"STAFF","notes":null,"confirmationCode":"ABCD2345",
    "plannedTableIds":[$planned],"planConflict":false,"arrivalRegisteredAt":null,"sessionId":null,"createdAt":"2026-11-19T10:00:00Z","version":$version}"""

private fun page(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private const val TABLES =
    """{"data":[{"id":"t1","number":1,"capacity":4,"status":"ACTIVE","operationalState":"LIBRE"},{"id":"t2","number":2,"capacity":4,"status":"ACTIVE","operationalState":"LIBRE"}],
    "meta":{"pagination":{"page":1,"perPage":100,"total":2,"lastPage":1}}}"""

class ReservationsPureTest {
    @Test
    fun `el telefono lleva lada internacional`() {
        assertTrue(isValidPhone("+523312345678"))
        assertFalse(isValidPhone("3312345678"))
        assertFalse(isValidPhone("+52"))
    }

    @Test
    fun `los instantes van en UTC sin fracciones`() {
        assertEquals("2026-11-20T16:00:00Z", isoInstant(NOW.plusMillis(450)))
    }

    @Test
    fun `no presentado solo despues de su hora y si esta confirmada`() {
        val r = ApiJson.decodeFromString(ReservationDto.serializer(), reservation("r1", "CONFIRMED"))
        assertFalse(canMarkNoShow(r, Instant.parse("2026-11-20T19:59:00Z")))
        assertTrue(canMarkNoShow(r, Instant.parse("2026-11-20T20:20:00Z")))
        assertFalse(canMarkNoShow(r.copy(status = "ARRIVED_WAITING"), Instant.parse("2026-11-20T20:20:00Z")))
    }

    @Test
    fun `de un 422 se toma el mensaje del campo`() {
        val details = Json.parseToJsonElement("""{"fields":{"startAt":["Fuera del horario de reservas de la sucursal."]}}""").jsonObject
        assertEquals("Fuera del horario de reservas de la sucursal.", fieldMessage(ApiFailure.Server(422, "VALIDATION_FAILED", "Datos inválidos", null, details)))
        assertNull(fieldMessage(ApiFailure.Server(422, "VALIDATION_FAILED", "Datos inválidos", null)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: ReservationsViewModel
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
                if (request.method == "GET") return gets[path]?.invoke() ?: MockResponse().setBody(page())
                posts += request to request.body.readUtf8()
                return answers.removeFirst()
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        gets["/branches/b1/tables"] = { MockResponse().setBody(TABLES) }
        gets["/branches/b1/reservations/waiting-queue"] = { MockResponse().setBody("""{"data":[]}""") }
        vm = ReservationsViewModel(ReservationsRepository(api), TablesRepository(api), "b1", ZONE, { NOW }, pollMillis = 3_600_000, newKey = { "clave-${++keys}" })
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

    @Test
    fun `carga las reservas del dia local con los numeros de mesa`() {
        gets["/branches/b1/reservations"] = { MockResponse().setBody(page(reservation("r1", "CONFIRMED", planned = "\"t1\",\"t2\""))) }
        loaded()
        val request = server.takeRequest().let { if (it.requestUrl!!.encodedPath.endsWith("/tables")) server.takeRequest() else it }
        // El día va de medianoche a medianoche en la hora de la sucursal.
        assertEquals("2026-11-20T06:00:00Z", request.requestUrl!!.queryParameter("from"))
        assertEquals("2026-11-21T06:00:00Z", request.requestUrl!!.queryParameter("to"))
        assertEquals(listOf(1, 2), vm.state.value.tableNumbers(vm.state.value.reservations[0].plannedTableIds))
    }

    @Test
    fun `crear y confirmar en un paso`() {
        loaded()
        vm.startCreate()
        assertEquals(LocalTime.of(10, 30), vm.state.value.creating!!.start)
        vm.setStart(LocalTime.of(14, 0))
        vm.setParty(4)
        vm.setName(" Ana López ")
        vm.setPhone("+52 33 1234 5678")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${reservation("r1", "PENDING")}}""")
        answers += MockResponse().setBody("""{"data":${reservation("r1", "CONFIRMED", version = 2)}}""")
        vm.confirmCreate()
        waitFor { vm.state.value.creating == null }
        assertEquals(
            """{"startAt":"2026-11-20T20:00:00Z","endAt":"2026-11-20T21:30:00Z","partySize":4,"customer":{"name":"Ana López","phone":"+523312345678"}}""",
            posts[0].second,
        )
        assertEquals("/branches/b1/reservations/r1/confirm", posts[1].first.path)
        assertEquals("\"1\"", posts[1].first.getHeader("If-Match"))
        assertEquals(R.string.reservation_created_confirmed, vm.state.value.notice?.text)
    }

    @Test
    fun `sin lugar la reserva queda sin confirmar y se avisa`() {
        loaded()
        vm.startCreate()
        vm.setName("Ana")
        vm.setPhone("+523312345678")
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${reservation("r1", "PENDING")}}""")
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"RESERVATION_NOT_AVAILABLE","message":"No hay"}}""")
        vm.confirmCreate()
        waitFor { vm.state.value.creating == null }
        assertEquals(R.string.reservation_created_not_confirmed, vm.state.value.notice?.text)
        assertEquals("ABCD2345", vm.state.value.notice?.arg)
    }

    @Test
    fun `un horario fuera de servicio deja el dialogo abierto con el error`() {
        loaded()
        vm.startCreate()
        vm.setName("Ana")
        vm.setPhone("+523312345678")
        answers += MockResponse().setResponseCode(422).setBody("""{"error":{"code":"VALIDATION_FAILED","message":"Datos","details":{"fields":{"startAt":["Fuera del horario"]}}}}""")
        vm.confirmCreate()
        waitFor { vm.state.value.creating?.failure != null }
        assertEquals(1, posts.size)
        assertFalse(vm.state.value.creating!!.submitting)
    }

    @Test
    fun `llegada con su version y clave`() {
        gets["/branches/b1/reservations"] = { MockResponse().setBody(page(reservation("r1", "CONFIRMED", version = 3))) }
        loaded()
        answers += MockResponse().setBody("""{"data":${reservation("r1", "ARRIVED_WAITING", version = 4)}}""")
        vm.arrive(vm.state.value.reservations[0])
        waitFor { posts.isNotEmpty() && !vm.state.value.working }
        val (request, body) = posts.single()
        assertEquals("/branches/b1/reservations/r1/arrive", request.path)
        assertEquals("\"3\"", request.getHeader("If-Match"))
        assertEquals("clave-1", request.getHeader("Idempotency-Key"))
        assertEquals("{}", body)
        assertEquals(R.string.reservation_arrived_done, vm.state.value.notice?.text)
    }

    @Test
    fun `cancelar exige motivo`() {
        gets["/branches/b1/reservations"] = { MockResponse().setBody(page(reservation("r1", "PENDING"))) }
        loaded()
        vm.startCancel(vm.state.value.reservations[0])
        vm.setCancelReason("x")
        assertFalse(vm.state.value.cancelling!!.canSubmit)
        vm.setCancelReason(" El cliente llamó ")
        answers += MockResponse().setBody("""{"data":${reservation("r1", "CANCELLED", version = 2)}}""")
        vm.confirmCancel()
        waitFor { vm.state.value.cancelling == null }
        assertEquals("""{"reason":"El cliente llamó"}""", posts.single().second)
    }

    @Test
    fun `sentar ofrece las mesas planeadas y abre la mesa`() {
        gets["/branches/b1/reservations"] = { MockResponse().setBody(page(reservation("r1", "CONFIRMED", planned = "\"t1\""))) }
        gets["/branches/b1/reservations/availability"] = { MockResponse().setBody("""{"data":{"available":true,"tableOptions":[{"tableIds":["t2"],"totalCapacity":4},{"tableIds":["t1"],"totalCapacity":4}]}}""") }
        loaded()
        vm.startSeat(vm.state.value.reservations[0])
        waitFor { vm.state.value.seating?.loading == false }
        assertEquals(listOf(listOf("t1"), listOf("t2")), vm.state.value.seating!!.options.map { it.tableIds })
        answers += MockResponse().setBody("""{"data":${reservation("r1", "CHECKED_IN", version = 2).replace("\"sessionId\":null", "\"sessionId\":\"s9\"")}}""")
        vm.confirmSeat()
        waitFor { vm.state.value.seated != null }
        assertEquals("""{"tableIds":["t1"]}""", posts.single().second)
        assertEquals("s9", vm.state.value.seated!!.sessionId)
        assertEquals(1, vm.state.value.seated!!.tableNumber)
    }

    @Test
    fun `si otra llego antes pide motivo y lo manda para saltarla`() {
        gets["/branches/b1/reservations"] = { MockResponse().setBody(page(reservation("r1", "CONFIRMED", planned = "\"t1\""))) }
        loaded()
        vm.startSeat(vm.state.value.reservations[0])
        waitFor { vm.state.value.seating?.loading == false }
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"RESERVATION_PRIORITY_CONFLICT","message":"Otra reserva tiene prioridad"}}""")
        vm.confirmSeat()
        waitFor { vm.state.value.seating?.priorityConflict == true }
        assertFalse(vm.state.value.seating!!.canSubmit)
        vm.setOverrideReason("Mesa del cumpleañero")
        answers += MockResponse().setBody("""{"data":${reservation("r1", "CHECKED_IN", version = 2).replace("\"sessionId\":null", "\"sessionId\":\"s9\"")}}""")
        vm.confirmSeat()
        waitFor { vm.state.value.seated != null }
        assertEquals("""{"tableIds":["t1"],"overridePriority":true,"overrideReason":"Mesa del cumpleañero"}""", posts[1].second)
        // Otra clave: el cuerpo cambió.
        assertTrue(posts[0].first.getHeader("Idempotency-Key") != posts[1].first.getHeader("Idempotency-Key"))
    }
}
