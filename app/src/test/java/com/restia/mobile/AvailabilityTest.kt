package com.restia.mobile

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.ui.kitchen.AvailabilityViewModel
import com.restia.mobile.ui.kitchen.availabilityOrder
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun product(id: String, name: String, category: String, order: Int, availability: String = "AVAILABLE") =
    MenuProductDto(id, name, null, category, order, MoneyDto("10.00", "MXN"), availability)

private fun productJson(id: String, name: String, availability: String) =
    """{"id":"$id","name":"$name","category":"Bebidas","displayOrder":1,"price":{"amount":"10.00","currency":"MXN"},"availability":"$availability"}"""

class AvailabilityPureTest {
    @Test
    fun `lo agotado va primero y luego por categoria y orden`() {
        val sorted = availabilityOrder(
            listOf(
                product("a", "Tacos", "Platos", 1),
                product("b", "Agua", "Bebidas", 2),
                product("c", "Limonada", "Bebidas", 1, "SOLD_OUT"),
                product("d", "Café", "Bebidas", 1),
            ),
        )
        assertEquals(listOf("c", "d", "b", "a"), sorted.map { it.id })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: AvailabilityViewModel
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                return routes["${request.method} ${request.requestUrl!!.encodedPath}"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        routes["GET /branches/b1/menu"] = {
            MockResponse().setBody(
                """{"data":{"menuVersion":1,"products":[${productJson("p1", "Agua", "AVAILABLE")},${productJson("p2", "Limonada", "SOLD_OUT")}],"modifiers":[]}}""",
            )
        }
        vm = AvailabilityViewModel(MenuRepository(api), "b1")
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

    private fun loaded() {
        vm.load()
        waitFor { vm.state.value.loaded }
    }

    @Test
    fun `carga la carta con lo agotado primero`() {
        loaded()
        assertEquals(listOf("Limonada", "Agua"), vm.state.value.products.map { it.name })
        assertEquals(1, vm.state.value.soldOut)
    }

    @Test
    fun `apagar un platillo se ve al momento y manda SOLD_OUT`() {
        routes["POST /branches/b1/products/p1/availability"] = { MockResponse().setBody("""{"data":{"id":"p1"}}""") }
        loaded()
        vm.toggle(vm.state.value.products.first { it.id == "p1" })
        waitFor { vm.state.value.saving.isEmpty() }
        assertEquals("SOLD_OUT", vm.state.value.products.first { it.id == "p1" }.availability)
        assertEquals("""{"availability":"SOLD_OUT"}""", requests.single { it.first.method == "POST" }.second)
        assertEquals(2, vm.state.value.soldOut)
        assertNull(vm.state.value.actionFailure)
    }

    @Test
    fun `si el servidor lo rechaza vuelve a como estaba y lo explica`() {
        routes["POST /branches/b1/products/p2/availability"] = {
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN","message":"x"}}""")
        }
        loaded()
        vm.toggle(vm.state.value.products.first { it.id == "p2" })
        waitFor { vm.state.value.actionFailure != null }
        assertEquals("SOLD_OUT", vm.state.value.products.first { it.id == "p2" }.availability)
        assertEquals("Limonada", vm.state.value.actionFailure!!.first)
        assertTrue(vm.state.value.saving.isEmpty())
    }
}
