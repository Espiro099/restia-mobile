package com.restia.mobile

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ReservationCustomerDto
import com.restia.mobile.core.network.ReservationDto
import com.restia.mobile.core.network.ReservationUpdateRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.account.SessionsRepository
import com.restia.mobile.data.reservations.ReservationsRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.account.SessionsViewModel
import com.restia.mobile.ui.reservations.EditReservationState
import com.restia.mobile.ui.reservations.ReservationsViewModel
import com.restia.mobile.ui.reservations.canEdit
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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

private val MX: ZoneId = ZoneId.of("America/Mexico_City")
private val TODAY: Instant = Instant.parse("2026-11-20T16:00:00Z")

private fun dto(status: String = "CONFIRMED", notes: String? = "Ventana") =
    ReservationDto("r1", status, "2026-11-20T20:00:00Z", "2026-11-20T22:00:00Z", 4, ReservationCustomerDto(name = "Ana López"), notes, "ABCD2345", version = 3)

private fun reservationJson(version: Int, start: String = "2026-11-20T20:00:00Z", party: Int = 4) =
    """{"id":"r1","status":"CONFIRMED","startAt":"$start","endAt":"2026-11-20T22:00:00Z","partySize":$party,
    "customer":{"customerId":null,"name":"Ana López","phone":"+523312345678"},"notes":"Ventana","confirmationCode":"ABCD2345",
    "plannedTableIds":[],"planConflict":false,"version":$version}"""

class ReservationEditPureTest {
    @Test
    fun `solo se editan las pendientes y confirmadas`() {
        assertTrue(canEdit(dto("PENDING")))
        assertTrue(canEdit(dto("CONFIRMED")))
        assertFalse(canEdit(dto("ARRIVED_WAITING")))
        assertFalse(canEdit(dto("CANCELLED")))
    }

    @Test
    fun `parte de lo que tiene la reserva en la hora local`() {
        val e = EditReservationState.of(dto(), MX)
        assertEquals(LocalTime.of(14, 0), e.start)
        assertEquals(120, e.durationMinutes)
        assertNull(e.changes(MX))
        assertFalse(e.canSubmit(MX))
    }

    @Test
    fun `manda solo lo que cambio`() {
        val e = EditReservationState.of(dto(), MX)
        assertEquals(ReservationUpdateRequest(partySize = 6), e.copy(partySize = 6).changes(MX))
        // Mover la hora mueve inicio y fin juntos, conservando la duración.
        assertEquals(
            ReservationUpdateRequest(startAt = "2026-11-20T20:30:00Z", endAt = "2026-11-20T22:30:00Z"),
            e.copy(start = LocalTime.of(14, 30)).changes(MX),
        )
        // Vaciar las notas las borra.
        assertEquals(ReservationUpdateRequest(notes = ""), e.copy(notes = "  ").changes(MX))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationEditFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val gets = mutableMapOf<String, () -> MockResponse>()
    private val answers = ArrayDeque<MockResponse>()
    private val writes = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "GET") {
                    return gets[path]?.invoke() ?: MockResponse().setBody("""{"data":[],"meta":{"pagination":{"page":1,"perPage":100,"total":0,"lastPage":1}}}""")
                }
                writes += request to request.body.readUtf8()
                return answers.removeFirst()
            }
        }
        server.start()
        // 127.0.0.1: en Linux "localhost" también es ::1 (ver OpenTableTest).
        api = Retrofit.Builder().baseUrl("http://127.0.0.1:${server.port}/")
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        gets["/branches/b1/reservations/waiting-queue"] = { MockResponse().setBody("""{"data":[]}""") }
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

    private fun reservations(): ReservationsViewModel {
        gets["/branches/b1/reservations"] = {
            MockResponse().setBody("""{"data":[${reservationJson(3)}],"meta":{"pagination":{"page":1,"perPage":100,"total":1,"lastPage":1}}}""")
        }
        val vm = ReservationsViewModel(ReservationsRepository(api), TablesRepository(api), "b1", MX, { TODAY }, pollMillis = 3_600_000, newKey = { "clave-${++keys}" })
        vm.refresh()
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `guardar manda PATCH con If-Match, clave y solo lo cambiado`() {
        val vm = reservations()
        answers += MockResponse().setBody("""{"data":${reservationJson(4, party = 6)}}""")
        vm.startEdit(vm.state.value.reservations.single())
        vm.editSetParty(6)
        vm.confirmEdit()
        waitFor { vm.state.value.editing == null }
        val (request, body) = writes.single()
        assertEquals("PATCH", request.method)
        assertEquals("/branches/b1/reservations/r1", request.requestUrl!!.encodedPath)
        assertEquals("\"3\"", request.getHeader("If-Match"))
        assertEquals("clave-1", request.getHeader("Idempotency-Key"))
        assertEquals("""{"partySize":6}""", body)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `sin mesa para el nuevo horario el dialogo sigue abierto y reintenta con la misma clave`() {
        val vm = reservations()
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"NO_TABLE_AVAILABLE","message":"No hay mesa"}}""")
        vm.startEdit(vm.state.value.reservations.single())
        vm.editShiftStart(60)
        vm.confirmEdit()
        waitFor { vm.state.value.editing?.failure != null }
        assertFalse(vm.state.value.editing!!.submitting)
        // Un 409 no es reintentable con la misma intención: otro intento lleva clave nueva.
        answers += MockResponse().setBody("""{"data":${reservationJson(4, start = "2026-11-20T21:00:00Z")}}""")
        vm.confirmEdit()
        waitFor { vm.state.value.editing == null }
        assertEquals(listOf("clave-1", "clave-2"), writes.map { it.first.getHeader("Idempotency-Key") })
    }

    private fun session(id: String, current: Boolean, type: String = "ANDROID", activity: String = "2026-11-20T15:00:00Z") =
        """{"id":"$id","clientType":"$type","status":"ACTIVE","current":$current,"deviceId":null,"createdAt":"2026-11-19T10:00:00Z",
        "lastActivityAt":"$activity","absoluteExpiresAt":"2026-11-27T10:00:00Z","revokedAt":null}"""

    @Test
    fun `mis sesiones muestra la actual primero y cierra otra`() {
        gets["/auth/sessions"] = {
            MockResponse().setBody(
                """{"data":[${session("w1", false, "WEB", "2026-11-20T15:30:00Z")},${session("a1", true)}],"meta":{"pagination":{"page":1,"perPage":50,"total":2,"lastPage":1}}}""",
            )
        }
        answers += MockResponse().setResponseCode(204)
        val vm = SessionsViewModel(SessionsRepository(api))
        vm.load()
        waitFor { vm.state.value.loaded }
        assertEquals(listOf("a1", "w1"), vm.state.value.sessions.map { it.id })
        // La actual no se cierra desde aquí.
        vm.ask(vm.state.value.sessions.first())
        assertNull(vm.state.value.confirming)
        vm.ask(vm.state.value.sessions.last())
        vm.revoke()
        waitFor { vm.state.value.revoked }
        assertEquals("/auth/sessions/w1/revoke", writes.single().first.requestUrl!!.encodedPath)
        assertEquals(listOf("a1"), vm.state.value.sessions.map { it.id })
        assertEquals("ACTIVE", server.takeRequest().requestUrl!!.queryParameter("status"))
    }

    @Test
    fun `una sesion que ya no existe se da por cerrada`() {
        gets["/auth/sessions"] = {
            MockResponse().setBody("""{"data":[${session("a1", true)},${session("a2", false)}],"meta":{"pagination":{"page":1,"perPage":50,"total":2,"lastPage":1}}}""")
        }
        answers += MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"x"}}""")
        val vm = SessionsViewModel(SessionsRepository(api))
        vm.load()
        waitFor { vm.state.value.loaded }
        vm.ask(vm.state.value.sessions.last())
        vm.revoke()
        waitFor { vm.state.value.revoked }
        assertEquals(1, vm.state.value.sessions.size)
    }
}
