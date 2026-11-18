package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.NotificationDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.notifications.NotificationsRepository
import com.restia.mobile.ui.notifications.NotificationsViewModel
import com.restia.mobile.ui.notifications.groupByDay
import com.restia.mobile.ui.notifications.noticeFailureText
import java.time.LocalDate
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun notice(id: String, at: String, read: Boolean = false) =
    NotificationDto(id, "ORDER_READY", "Mesa 4 · orden lista", "1 platillo listo para servir.", 4, "s1", null, 1, if (read) at else null, at, at)

private fun json(n: NotificationDto) =
    """{"id":"${n.id}","type":"${n.type}","title":"${n.title}","body":"${n.body}","tableNumber":${n.tableNumber},"sessionId":"s1","checkId":null,""" +
        """"count":1,"readAt":${n.readAt?.let { "\"$it\"" } ?: "null"},"createdAt":"${n.createdAt}","updatedAt":"${n.updatedAt}"}"""

class NotificationsPureTest {
    @Test
    fun `agrupa por dia del mas reciente al mas antiguo`() {
        val zone = ZoneId.of("America/Mexico_City")
        val items = listOf(
            notice("a", "2026-11-17T18:00:00Z"),
            notice("b", "2026-11-16T05:30:00Z"), // 23:30 del 15 en México
            notice("c", "2026-11-17T15:00:00Z"),
            notice("d", "no-es-fecha"),
        )
        val groups = groupByDay(items, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 15), null), groups.map { it.first })
        assertEquals(listOf("a", "c"), groups[0].second.map { it.id })
    }

    @Test
    fun `cada falla tiene su texto`() {
        assertEquals(R.string.error_network, noticeFailureText(ApiFailure.Network))
        assertEquals(R.string.error_server, noticeFailureText(ApiFailure.Server(503, "SERVICE_BUSY", null, null)))
        assertNull(noticeFailureText(ApiFailure.Server(422, "VALIDATION_FAILED", "del servidor", null)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: NotificationsViewModel
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes["${request.method} ${request.requestUrl!!.encodedPath}"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = NotificationsViewModel(NotificationsRepository(api), "b1", pollMillis = 3_600_000)
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

    private fun page(vararg items: NotificationDto, unread: Int) =
        MockResponse().setBody("""{"data":[${items.joinToString { json(it) }}],"meta":{"unreadCount":$unread,"pagination":{"page":1,"perPage":50,"total":${items.size},"lastPage":1}}}""")

    @Test
    fun `carga los avisos y cuantos faltan por leer`() {
        routes["GET /branches/b1/notifications"] = { page(notice("a", "2026-11-17T18:00:00Z"), notice("b", "2026-11-17T17:00:00Z", read = true), unread = 1) }
        vm.refresh()
        waitFor { vm.state.value.loaded }
        assertEquals(1, vm.state.value.unread)
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.id })
        assertEquals("50", requests.single().requestUrl!!.queryParameter("perPage"))
    }

    @Test
    fun `abrir un aviso lo marca leido de inmediato`() {
        val unread = notice("a", "2026-11-17T18:00:00Z")
        routes["GET /branches/b1/notifications"] = { page(unread, unread = 1) }
        routes["POST /branches/b1/notifications/a/read"] = { MockResponse().setBody("""{"data":${json(unread.copy(readAt = "2026-11-17T18:05:00Z"))}}""") }
        vm.refresh()
        waitFor { vm.state.value.loaded }
        vm.markRead(vm.state.value.items.single())
        assertEquals(0, vm.state.value.unread)
        assertNotNull(vm.state.value.items.single().readAt)
        waitFor { requests.any { it.method == "POST" } }
        // Uno ya leído no vuelve a pedirse.
        vm.markRead(vm.state.value.items.single())
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test
    fun `marcar todos deja la marca en cero y si falla lo explica`() {
        routes["GET /branches/b1/notifications"] = { page(notice("a", "2026-11-17T18:00:00Z"), notice("b", "2026-11-17T17:00:00Z"), unread = 2) }
        routes["POST /branches/b1/notifications/read-all"] = { MockResponse().setResponseCode(503).setBody("""{"error":{"code":"SERVICE_BUSY","message":"x"}}""") }
        vm.refresh()
        waitFor { vm.state.value.loaded }
        vm.markAllRead()
        waitFor { vm.state.value.failure != null }
        assertEquals(2, vm.state.value.unread)

        routes["POST /branches/b1/notifications/read-all"] = { MockResponse().setBody("""{"data":{"updated":2,"unreadCount":0}}""") }
        vm.markAllRead()
        waitFor { vm.state.value.unread == 0 && !vm.state.value.markingAll }
        assertTrue(vm.state.value.items.all { it.readAt != null })
    }

    @Test
    fun `sin red conserva lo ultimo que vio`() {
        routes["GET /branches/b1/notifications"] = { page(notice("a", "2026-11-17T18:00:00Z"), unread = 1) }
        vm.refresh()
        waitFor { vm.state.value.loaded }
        routes["GET /branches/b1/notifications"] = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        vm.refresh()
        waitFor { vm.state.value.failure != null }
        assertEquals(ApiFailure.Network, vm.state.value.failure)
        assertEquals(1, vm.state.value.items.size)
    }
}
