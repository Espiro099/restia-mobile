package com.restia.mobile

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.IngredientDto
import com.restia.mobile.core.network.InventoryCountLineDto
import com.restia.mobile.core.network.FoodSafetyBlockDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.inventory.InventoryRepository
import com.restia.mobile.data.inventory.isLow
import com.restia.mobile.data.inventory.mergeCountLines
import com.restia.mobile.data.inventory.normalizeCost
import com.restia.mobile.data.inventory.normalizeQuantity
import com.restia.mobile.ui.inventory.FormProblem
import com.restia.mobile.ui.inventory.InventoryAction
import com.restia.mobile.ui.inventory.InventoryFilter
import com.restia.mobile.ui.inventory.InventoryForm
import com.restia.mobile.ui.inventory.InventoryPermissions
import com.restia.mobile.ui.inventory.InventoryViewModel
import com.restia.mobile.ui.inventory.formProblem
import com.restia.mobile.ui.inventory.visibleIngredients
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun ingredient(id: String, name: String, stock: String, min: String, blocked: Boolean = false) =
    IngredientDto(id, id.uppercase(), name, "KG", min, stock, foodSafetyBlock = if (blocked) FoodSafetyBlockDto("Cadena de frío rota") else null)

class InventoryRulesTest {
    @Test
    fun `cantidades como las pide la API`() {
        assertEquals("1.5", normalizeQuantity(" 1,50 "))
        assertEquals("3", normalizeQuantity("3"))
        assertNull(normalizeQuantity("0"))
        assertNull(normalizeQuantity("-2"))
        assertNull(normalizeQuantity("1.23456"))
        assertNull(normalizeQuantity("dos"))
        assertEquals("25.00", normalizeCost("$25"))
        assertEquals("0.00", normalizeCost("0"))
        assertEquals("12.375", normalizeCost("12.375"))
        assertNull(normalizeCost("abc"))
    }

    @Test
    fun `bajo minimo solo con minimo mayor a cero`() {
        assertTrue(ingredient("a", "A", "1.5", "3").isLow())
        assertFalse(ingredient("b", "B", "3", "3").isLow())
        assertFalse(ingredient("c", "C", "0", "0").isLow())
    }

    @Test
    fun `primero bloqueado y bajo minimo, luego por nombre, y se filtra sin acentos`() {
        val all = listOf(
            ingredient("t", "Tortilla", "50", "10"),
            ingredient("j", "Jitomate", "1", "3"),
            ingredient("c", "Carne", "5", "2", blocked = true),
            ingredient("a", "Aguacate", "8", "2"),
        )
        assertEquals(listOf("Carne", "Jitomate", "Aguacate", "Tortilla"), visibleIngredients(all, InventoryFilter.ALL, "").map { it.name })
        assertEquals(listOf("Jitomate"), visibleIngredients(all, InventoryFilter.LOW, "").map { it.name })
        assertEquals(listOf("Carne"), visibleIngredients(all, InventoryFilter.BLOCKED, "").map { it.name })
        assertEquals(listOf("Jitomate"), visibleIngredients(all, InventoryFilter.ALL, "JÍTO").map { it.name })
    }

    @Test
    fun `cada accion pide sus datos`() {
        assertEquals(FormProblem.QUANTITY, formProblem(InventoryAction.WASTE, InventoryForm(quantity = "0", reason = "Se cayó")))
        assertEquals(FormProblem.REASON, formProblem(InventoryAction.WASTE, InventoryForm(quantity = "1", reason = "x")))
        assertNull(formProblem(InventoryAction.WASTE, InventoryForm(quantity = "1", reason = "Se cayó")))
        assertEquals(FormProblem.COST, formProblem(InventoryAction.RECEIVE, InventoryForm(quantity = "5")))
        assertEquals(FormProblem.SUPPLIER, formProblem(InventoryAction.RECEIVE, InventoryForm(quantity = "5", unitCost = "20")))
        assertNull(formProblem(InventoryAction.COUNT, InventoryForm(quantity = "0")))
        assertEquals(FormProblem.REASON, formProblem(InventoryAction.BLOCK, InventoryForm()))
    }

    @Test
    fun `las acciones siguen los permisos y no se bloquea dos veces`() {
        val kitchen = InventoryPermissions(waste = true, receive = true, count = true, block = true)
        assertEquals(
            listOf(InventoryAction.RECEIVE, InventoryAction.WASTE, InventoryAction.COUNT, InventoryAction.BLOCK),
            kitchen.actions(ingredient("a", "A", "1", "0")),
        )
        assertFalse(InventoryAction.BLOCK in kitchen.actions(ingredient("a", "A", "1", "0", blocked = true)))
        assertEquals(emptyList<InventoryAction>(), InventoryPermissions(false, false, false, false).actions(ingredient("a", "A", "1", "0")))
    }

    @Test
    fun `el conteo reemplaza la linea del mismo insumo y conserva las otras`() {
        val lines = listOf(InventoryCountLineDto("a", "1", "t1"), InventoryCountLineDto("b", "2", "t1"))
        assertEquals(
            listOf(InventoryCountLineDto("b", "2", "t1"), InventoryCountLineDto("a", "4", "t2")),
            mergeCountLines(lines, InventoryCountLineDto("a", "4", "t2")),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryFlowTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var vm: InventoryViewModel
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private val clock = Clock.fixed(Instant.parse("2026-11-24T18:30:00.123Z"), ZoneOffset.UTC)

    private val ingredientsJson =
        """{"data":[{"id":"i1","code":"TOMATE","name":"Jitomate","baseUnit":"KG","minStock":"3","stock":"1.5","status":"ACTIVE","foodSafetyBlock":null,"version":2},""" +
            """{"id":"i2","code":"TORTILLA","name":"Tortilla","baseUnit":"PIECE","minStock":"50","stock":"200","status":"ACTIVE","foodSafetyBlock":null,"version":1}],""" +
            """"meta":{"pagination":{"page":1,"lastPage":1}}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return routes["${request.method} $path"]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        routes["GET /branches/b1/ingredients"] = { MockResponse().setBody(ingredientsJson) }
        vm = main.track(InventoryViewModel(InventoryRepository(api, clock), "b1", settleMillis = 0))
    }

    @After
    fun tearDown() {
        server.shutdown()
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

    private fun sent(method: String, path: String) = requests.last { it.first.method == method && it.first.requestUrl!!.encodedPath == path }

    @Test
    fun `carga los insumos activos con lo bajo primero`() {
        loaded()
        assertEquals(listOf("Jitomate", "Tortilla"), vm.state.value.visible.map { it.name })
        assertEquals(1, vm.state.value.low)
        assertEquals("ACTIVE", sent("GET", "/branches/b1/ingredients").first.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun `consulta el consumo reciente del insumo seleccionado con su platillo`() {
        routes["GET /branches/b1/inventory-movements"] = {
            MockResponse().setBody(
                """{"data":[{"quantity":"-0.120","sourceProductName":"Tacos al pastor","recipeVersion":2,"createdAt":"2026-11-24T18:30:00Z"}],"meta":{}}""",
            )
        }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.loadConsumption()
        waitFor { vm.state.value.consumption != null }
        val row = vm.state.value.consumption!!.single()
        assertEquals("Tacos al pastor", row.sourceProductName)
        assertEquals(2, row.recipeVersion)
        assertEquals("-0.120", row.quantity)
        val query = sent("GET", "/branches/b1/inventory-movements").first.requestUrl!!
        assertEquals("i1", query.queryParameter("ingredientId"))
        assertEquals("CONSUMPTION", query.queryParameter("type"))
        assertEquals("5", query.queryParameter("perPage"))
        vm.select(null)
        assertEquals(null, vm.state.value.consumption)
    }

    @Test
    fun `registra merma con clave de idempotencia y vuelve a cargar hasta ver el stock nuevo`() {
        routes["POST /branches/b1/inventory-movements"] = { MockResponse().setResponseCode(201).setBody("""{"data":{"id":"m1"}}""") }
        loaded()
        // El servidor proyecta la merma tras dos consultas más: se deja de preguntar al ver el stock nuevo.
        var calls = 0
        routes["GET /branches/b1/ingredients"] = {
            calls++
            MockResponse().setBody(if (calls < 3) ingredientsJson else ingredientsJson.replace("\"stock\":\"1.5\"", "\"stock\":\"1\""))
        }
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.WASTE)
        vm.edit(InventoryForm(quantity = "0,5", reason = "Se echó a perder"))
        vm.save()
        waitFor { vm.state.value.ingredients.first { it.id == "i1" }.stock == "1" && !vm.state.value.refreshing }
        val (request, body) = sent("POST", "/branches/b1/inventory-movements")
        assertEquals("""{"ingredientId":"i1","type":"WASTE","quantity":"0.5","reason":"Se echó a perder"}""", body)
        assertNotNull(request.getHeader("Idempotency-Key"))
        assertEquals(InventoryAction.WASTE to "Jitomate", vm.state.value.done)
        assertNull(vm.state.value.selected)
        Thread.sleep(200)
        assertEquals(4, requests.count { it.first.requestUrl!!.encodedPath == "/branches/b1/ingredients" })
    }

    @Test
    fun `recibe mercancia con proveedor, costo y hora`() {
        routes["POST /branches/b1/inventory-receipts"] = { MockResponse().setResponseCode(201).setBody("""{"data":{"id":"r1"}}""") }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.RECEIVE)
        vm.edit(InventoryForm(quantity = "10", unitCost = "22.5", supplier = " Central de Abasto "))
        vm.save()
        waitFor { vm.state.value.done != null }
        assertEquals(
            """{"supplierName":"Central de Abasto","receivedAt":"2026-11-24T18:30:00Z","lines":[{"ingredientId":"i1","quantity":"10","unitCost":{"amount":"22.50","currency":"MXN"}}]}""",
            sent("POST", "/branches/b1/inventory-receipts").second,
        )
    }

    @Test
    fun `sin conteo abierto lo abre y anota la linea con If-Match`() {
        routes["GET /branches/b1/inventory-counts"] = { MockResponse().setBody("""{"data":[],"meta":{}}""") }
        routes["POST /branches/b1/inventory-counts"] = { MockResponse().setResponseCode(201).setBody("""{"data":{"id":"c1","status":"OPEN","lines":[],"version":1}}""") }
        routes["PUT /branches/b1/inventory-counts/c1/lines"] = {
            MockResponse().setBody("""{"data":{"id":"c1","status":"OPEN","lines":[{"ingredientId":"i1","counted":"2","countedAt":"2026-11-24T18:30:00Z"}],"version":2}}""")
        }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.COUNT)
        vm.edit(InventoryForm(quantity = "2"))
        vm.save()
        waitFor { vm.state.value.done != null }
        val (put, body) = sent("PUT", "/branches/b1/inventory-counts/c1/lines")
        assertEquals("\"1\"", put.getHeader("If-Match"))
        assertEquals("""{"lines":[{"ingredientId":"i1","counted":"2","countedAt":"2026-11-24T18:30:00Z"}]}""", body)
    }

    @Test
    fun `con conteo abierto conserva lo que otros ya contaron`() {
        routes["GET /branches/b1/inventory-counts"] = {
            MockResponse().setBody("""{"data":[{"id":"c9","status":"OPEN","lines":[{"ingredientId":"i2","counted":"180","countedAt":"2026-11-24T18:00:00Z"}],"version":5}],"meta":{}}""")
        }
        routes["PUT /branches/b1/inventory-counts/c9/lines"] = { MockResponse().setBody("""{"data":{"id":"c9","status":"OPEN","lines":[],"version":6}}""") }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.COUNT)
        vm.edit(InventoryForm(quantity = "0"))
        vm.save()
        waitFor { vm.state.value.done != null }
        assertEquals(
            """{"lines":[{"ingredientId":"i2","counted":"180","countedAt":"2026-11-24T18:00:00Z"},{"ingredientId":"i1","counted":"0","countedAt":"2026-11-24T18:30:00Z"}]}""",
            sent("PUT", "/branches/b1/inventory-counts/c9/lines").second,
        )
        assertTrue(requests.none { it.first.method == "POST" })
    }

    @Test
    fun `si el servidor lo rechaza se queda el formulario y lo explica`() {
        routes["POST /branches/b1/ingredients/i1/food-safety"] = { MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN","message":"Sin permiso"}}""") }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.BLOCK)
        vm.edit(InventoryForm(reason = "Huele raro"))
        vm.save()
        waitFor { vm.state.value.actionFailure != null }
        assertEquals(InventoryAction.BLOCK, vm.state.value.action)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.done)
    }

    @Test
    fun `si el stock nunca cambia deja de preguntar tras cinco intentos`() {
        routes["POST /branches/b1/inventory-receipts"] = { MockResponse().setResponseCode(201).setBody("""{"data":{"id":"r1"}}""") }
        loaded()
        vm.select(vm.state.value.ingredients.first())
        vm.start(InventoryAction.RECEIVE)
        vm.edit(InventoryForm(quantity = "1", unitCost = "10", supplier = "Proveedor"))
        vm.save()
        waitFor { requests.count { it.first.requestUrl!!.encodedPath == "/branches/b1/ingredients" } == 6 && !vm.state.value.refreshing }
        Thread.sleep(200)
        assertEquals(6, requests.count { it.first.requestUrl!!.encodedPath == "/branches/b1/ingredients" })
    }
}
