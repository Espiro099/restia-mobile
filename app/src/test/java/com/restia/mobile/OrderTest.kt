package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.MenuModifierDto
import com.restia.mobile.core.network.MenuOptionDto
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.storage.ContactTracker
import com.restia.mobile.data.orders.CartLine
import com.restia.mobile.data.orders.DraftRepository
import com.restia.mobile.data.orders.MAX_QUANTITY
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.orders.SelectedOption
import com.restia.mobile.data.orders.addToCart
import com.restia.mobile.data.orders.invalidModifiers
import com.restia.mobile.ui.orders.OrderViewModel
import com.restia.mobile.ui.orders.moneyText
import com.restia.mobile.ui.tables.hasSession
import java.math.BigDecimal
import java.time.Instant
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun money(amount: String) = MoneyDto(amount, "MXN")
private val agua = MenuProductDto("p1", "Agua", null, "Bebidas", 1, money("20.50"), "AVAILABLE")
private val tamano = MenuModifierDto("m1", "Tamaño", 1, 1, listOf(MenuOptionDto("o1", "Chico", money("0.00")), MenuOptionDto("o2", "Grande", money("10.10"))))
private val extras = MenuModifierDto("m2", "Extras", 0, 2, listOf(MenuOptionDto("e1", "Limón", money("1.00")), MenuOptionDto("e2", "Hielo", money("0.00")), MenuOptionDto("e3", "Sal", money("0.00"))))
private val sopa = MenuProductDto("p2", "Sopa", null, "Platos", 1, money("50.00"), "AVAILABLE", listOf("m1", "m2"))
private val agotado = MenuProductDto("p3", "Pastel", null, "Postres", 1, money("30.00"), "SOLD_OUT")

private const val MENU_JSON = """{"data":{"menuVersion":3,"products":[
{"id":"p1","name":"Agua","category":"Bebidas","displayOrder":1,"price":{"amount":"20.50","currency":"MXN"},"availability":"AVAILABLE","modifierIds":[]},
{"id":"p2","name":"Sopa","category":"Platos","displayOrder":1,"price":{"amount":"50.00","currency":"MXN"},"availability":"AVAILABLE","modifierIds":["m1","m2"]},
{"id":"p3","name":"Pastel","category":"Postres","displayOrder":1,"price":{"amount":"30.00","currency":"MXN"},"availability":"SOLD_OUT","modifierIds":[]}],
"modifiers":[
{"id":"m1","name":"Tamaño","minSelections":1,"maxSelections":1,"options":[{"id":"o1","name":"Chico","priceDelta":{"amount":"0.00","currency":"MXN"}},{"id":"o2","name":"Grande","priceDelta":{"amount":"10.10","currency":"MXN"}}]},
{"id":"m2","name":"Extras","minSelections":0,"maxSelections":2,"options":[{"id":"e1","name":"Limón","priceDelta":{"amount":"1.00","currency":"MXN"}},{"id":"e2","name":"Hielo","priceDelta":{"amount":"0.00","currency":"MXN"}},{"id":"e3","name":"Sal","priceDelta":{"amount":"0.00","currency":"MXN"}}]}]},"meta":{}}"""
private const val ORDER_JSON = """{"data":{"id":"or1","clientOrderUuid":"x","status":"OPEN","total":{"amount":"20.50","currency":"MXN"}},"meta":{}}"""

private fun conflict(code: String, status: Int = 409) =
    MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"mensaje $code","requestId":"r"}}""")

class OrderCartTest {
    @Test
    fun `el estimado usa decimales exactos`() {
        val line = CartLine(sopa, listOf(SelectedOption(tamano, tamano.options[1])), 3, null)
        assertEquals(0, BigDecimal("180.30").compareTo(line.estimate))
        assertEquals("$180.30 MXN", moneyText(line.estimate, "MXN"))
    }

    @Test
    fun `lineas iguales se unen salvo con notas o al pasar el maximo`() {
        val a = CartLine(agua, emptyList(), 2, null)
        assertEquals(5, addToCart(listOf(a), a.copy(quantity = 3)).single().quantity)
        assertEquals(2, addToCart(listOf(a), a.copy(notes = "sin hielo")).size)
        assertEquals(2, addToCart(listOf(a.copy(quantity = MAX_QUANTITY)), a).size)
    }

    @Test
    fun `modificadores deben cumplir minimo y maximo`() {
        assertEquals(listOf(tamano), invalidModifiers(listOf(tamano, extras), emptyMap()))
        assertTrue(invalidModifiers(listOf(tamano, extras), mapOf("m1" to setOf("o1"))).isEmpty())
        assertEquals(listOf(extras), invalidModifiers(listOf(extras), mapOf("m2" to setOf("e1", "e2", "e3"))))
    }

    @Test
    fun `solo una mesa en servicio con sesion muestra rondas`() {
        val t = TableDto("t", 1, 4, null, "ACTIVE", "CONSUMIENDO", "s")
        assertTrue(hasSession(t))
        assertTrue(hasSession(t.copy(operationalState = "CUENTA_SOLICITADA")))
        assertFalse(hasSession(t.copy(operationalState = "POR_LIMPIAR")))
        assertFalse(hasSession(t.copy(operationalState = "LIBRE")))
        assertFalse(hasSession(t.copy(currentSessionId = null)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OrderFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private lateinit var vm: OrderViewModel
    private val posts = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var uuids = 0
    private val blobs = InMemoryBlobStore()
    private var nowMillis = 1_000_000L
    private val contact = ContactTracker(blobs) { nowMillis }
    private var answers = ArrayDeque<MockResponse>()
    private var menuAnswer: () -> MockResponse = { MockResponse().setBody(MENU_JSON).setHeader("ETag", "\"3\"") }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return menuAnswer()
                posts += request
                bodies += request.body.readUtf8()
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

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("tiempo agotado", condition())
    }

    private fun start() {
        vm = newViewModel()
        waitFor { vm.state.value.ready && (vm.state.value.menu != null || vm.state.value.menuFailure != null) }
    }

    private fun newViewModel(user: String = "u1") = OrderViewModel(
        MenuRepository(api), OrderRepository(api), DraftRepository(blobs), contact, "b1", user, "s1", 7,
        newUuid = { "uuid-${++uuids}" }, now = { Instant.parse("2026-11-03T10:00:00Z") },
    )

    private fun addAgua(quantity: Int = 1) {
        vm.selectProduct(vm.state.value.menu!!.products.first { it.id == "p1" })
        vm.setQuantity(quantity)
        vm.addEditorToCart()
    }

    private fun send() {
        vm.send()
        waitFor { !vm.state.value.sending }
    }

    @Test
    fun `el menu en disco sirve sin red al volver a abrir la app`() = runBlocking {
        MenuRepository(api, blobs).load("b1")
        menuAnswer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val load = (MenuRepository(api, blobs).load("b1") as ApiResult.Success).value
        assertEquals(3, load.menu.menuVersion)
        assertTrue(load.stale)
    }

    @Test
    fun `la comanda sobrevive al cierre de la app`() {
        start()
        addAgua(2)
        waitFor { blobs.names("draft-").isNotEmpty() }
        start()
        assertEquals(2, vm.state.value.cart.single().quantity)
        assertFalse(vm.state.value.uncertain)
    }

    @Test
    fun `envio interrumpido por el cierre queda en duda y reintenta con los mismos identificadores`() {
        start()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua()
        send()
        start()
        assertTrue(vm.state.value.uncertain)
        assertTrue(vm.state.value.reviewing)
        send()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertEquals(bodies[0], bodies[1])
        assertTrue(vm.state.value.sent)
        waitFor { blobs.names("draft-").isEmpty() }
    }

    @Test
    fun `un borrador no se envia solo al abrir la app`() {
        start()
        addAgua()
        waitFor { blobs.names("draft-").isNotEmpty() }
        start()
        assertTrue(posts.isEmpty())
    }

    @Test
    fun `otro usuario no ve el borrador - CRED-23`() = runBlocking {
        start()
        addAgua()
        waitFor { blobs.names("draft-").isNotEmpty() }
        val otro = newViewModel("u2")
        waitFor { otro.state.value.ready }
        assertTrue(otro.state.value.cart.isEmpty())
        assertTrue(DraftRepository(blobs).listFor("u2", "b1").isEmpty())
        assertEquals(7, DraftRepository(blobs).listFor("u1", "b1").single().tableNumber)
    }

    @Test
    fun `tras 12 horas sin conexion es solo lectura pero se puede enviar`() {
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        contact.record()
        start()
        addAgua()
        waitFor { blobs.names("draft-").isNotEmpty() }
        nowMillis += 12 * 60 * 60 * 1000L + 1
        start()
        assertTrue(vm.state.value.readOnly)
        vm.selectProduct(vm.state.value.menu!!.products.first { it.id == "p1" })
        assertNull(vm.state.value.editor)
        vm.removeLine(vm.state.value.cart.single())
        assertEquals(1, vm.state.value.cart.size)
        send()
        assertTrue(vm.state.value.sent)
    }

    @Test
    fun `borrador danado se ignora`() = runBlocking {
        blobs.write("draft-u1_s1", "no es json")
        start()
        assertTrue(vm.state.value.cart.isEmpty())
        assertTrue(DraftRepository(blobs).listFor("u1", "b1").isEmpty())
    }

    @Test
    fun `carga el menu`() {
        start()
        assertEquals(3, vm.state.value.menu?.products?.size)
    }

    @Test
    fun `menu con el mismo ETag reutiliza el anterior y lo envia al servidor`() = runBlocking {
        val repo = MenuRepository(api)
        assertTrue(repo.load("b1") is ApiResult.Success)
        menuAnswer = { MockResponse().setResponseCode(304) }
        val again = repo.load("b1") as ApiResult.Success
        assertEquals(3, again.value.menu.menuVersion)
        assertFalse(again.value.stale)
        server.takeRequest()
        assertEquals("\"3\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `sin red conserva el menu ya cargado`() = runBlocking {
        val repo = MenuRepository(api)
        repo.load("b1")
        menuAnswer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertTrue((repo.load("b1") as ApiResult.Success).value.stale)
    }

    @Test
    fun `sin red y sin menu muestra el error`() {
        menuAnswer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        start()
        assertEquals(ApiFailure.Network, vm.state.value.menuFailure)
    }

    @Test
    fun `un producto agotado no se puede elegir`() {
        start()
        vm.selectProduct(agotado)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun `no agrega si falta un modificador obligatorio y si lo envia completo`() {
        start()
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        vm.selectProduct(vm.state.value.menu!!.products.first { it.id == "p2" })
        vm.addEditorToCart()
        assertTrue(vm.state.value.editor!!.showInvalid)
        assertTrue(vm.state.value.cart.isEmpty())
        vm.toggleOption("m1", "o1")
        vm.toggleOption("m1", "o2")
        assertEquals(setOf("o2"), vm.state.value.editor!!.selected["m1"])
        vm.toggleOption("m2", "e1")
        vm.toggleOption("m2", "e2")
        vm.toggleOption("m2", "e3")
        assertEquals(setOf("e1", "e2"), vm.state.value.editor!!.selected["m2"])
        vm.setNotes("  sin cebolla  ")
        vm.addEditorToCart()
        send()
        assertEquals(
            """{"sessionId":"s1","clientOrderUuid":"uuid-1","capturedAt":"2026-11-03T10:00:00Z","items":[{"productId":"p2","quantity":1,"modifierOptions":[{"modifierId":"m1","optionId":"o2"},{"modifierId":"m2","optionId":"e1"},{"modifierId":"m2","optionId":"e2"}],"notes":"sin cebolla"}]}""",
            bodies.single(),
        )
        assertEquals("uuid-2", posts.single().getHeader("Idempotency-Key"))
        assertEquals("/branches/b1/orders", posts.single().path)
    }

    @Test
    fun `enviar vacia la comanda y avisa`() {
        start()
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua()
        send()
        assertTrue(vm.state.value.sent)
        assertTrue(vm.state.value.cart.isEmpty())
    }

    @Test
    fun `respuesta perdida bloquea la comanda y el reintento repite identificadores`() {
        start()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua()
        send()
        assertTrue(vm.state.value.uncertain)
        assertEquals(1, vm.state.value.cart.size)

        vm.selectProduct(vm.state.value.menu!!.products.first { it.id == "p1" })
        assertNull(vm.state.value.editor)
        vm.removeLine(vm.state.value.cart.single())
        assertEquals(1, vm.state.value.cart.size)

        send()
        assertEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertEquals(bodies[0], bodies[1])
        assertTrue(vm.state.value.sent)
    }

    @Test
    fun `error del servidor tambien queda en duda`() {
        start()
        answers.add(conflict("SERVICE_BUSY", 503))
        addAgua()
        send()
        assertTrue(vm.state.value.uncertain)
        assertNotNull(vm.state.value.failure)
    }

    @Test
    fun `descartar una comanda en duda la vacia y la siguiente usa otros identificadores`() {
        start()
        answers.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua()
        send()
        vm.discard()
        assertTrue(vm.state.value.cart.isEmpty())
        assertFalse(vm.state.value.uncertain)
        addAgua()
        send()
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
        assertNotEquals(bodies[0], bodies[1])
    }

    @Test
    fun `producto no disponible no queda en duda, permite editar y recarga el menu`() {
        start()
        answers.add(conflict("PRODUCT_UNAVAILABLE"))
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua()
        val gets = server.requestCount
        send()
        assertFalse(vm.state.value.uncertain)
        assertEquals("PRODUCT_UNAVAILABLE", (vm.state.value.failure as ApiFailure.Server).code)
        waitFor { server.requestCount > gets + 1 }
        addAgua()
        send()
        assertEquals(2, posts.size)
        assertNotEquals(posts[0].getHeader("Idempotency-Key"), posts[1].getHeader("Idempotency-Key"))
    }

    @Test
    fun `sesion inactiva muestra el motivo sin reintentar`() {
        start()
        answers.add(conflict("SESSION_NOT_ACTIVE"))
        addAgua()
        send()
        assertFalse(vm.state.value.uncertain)
        assertEquals(1, posts.size)
        assertEquals("SESSION_NOT_ACTIVE", (vm.state.value.failure as ApiFailure.Server).code)
    }

    @Test
    fun `doble toque en enviar manda una sola comanda`() {
        start()
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON).setBodyDelay(200, TimeUnit.MILLISECONDS))
        addAgua()
        vm.send()
        vm.send()
        waitFor { vm.state.value.sent }
        assertEquals(1, posts.size)
    }

    @Test
    fun `el total del estimado no se envia al servidor`() {
        start()
        answers.add(MockResponse().setResponseCode(201).setBody(ORDER_JSON))
        addAgua(2)
        send()
        assertFalse(bodies.single().contains("price") || bodies.single().contains("20.50"))
    }
}
