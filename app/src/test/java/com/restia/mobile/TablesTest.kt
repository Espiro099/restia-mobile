package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.tables.TablesViewModel
import com.restia.mobile.ui.tables.tableStateLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun table(number: Int, state: String? = "LIBRE", status: String = "ACTIVE") =
    """{"id":"t$number","branchId":"b1","number":$number,"capacity":4,"status":"$status","operationalState":${if (state == null) "null" else "\"$state\""},"version":1}"""

private fun page(items: List<String>, current: Int, last: Int) =
    """{"data":[${items.joinToString()}],"meta":{"pagination":{"page":$current,"perPage":100,"total":3,"lastPage":$last}}}"""

class TablesRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: TablesRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        repository = TablesRepository(api)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `recorre todas las paginas y ordena por numero`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl?.queryParameter("page")) {
                "1" -> MockResponse().setBody(page(listOf(table(3), table(1)), 1, 2))
                else -> MockResponse().setBody(page(listOf(table(2)), 2, 2))
            }
        }
        val result = repository.list("b1") as ApiResult.Success
        assertEquals(listOf(1, 2, 3), result.value.map { it.number })
        val first = server.takeRequest()
        assertEquals("/branches/b1/tables?page=1&perPage=100", first.path)
    }

    @Test
    fun `sin paginacion en la respuesta no sigue pidiendo`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[${table(1)}],"meta":{}}"""))
        assertTrue(repository.list("b1") is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `un lastPage desmedido no pide paginas sin limite`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(page(emptyList(), 1, 100000))
        }
        repository.list("b1")
        assertEquals(20, server.requestCount)
    }

    @Test
    fun `mesa con estado nulo o desconocido se puede leer`() = runBlocking {
        server.enqueue(MockResponse().setBody(page(listOf(table(1, null, "MAINTENANCE"), table(2, "NUEVO_ESTADO")), 1, 1)))
        val tables = (repository.list("b1") as ApiResult.Success).value
        assertEquals(R.string.table_maintenance, tableStateLabel(tables[0]))
        assertEquals(R.string.table_unknown, tableStateLabel(tables[1]))
    }

    @Test
    fun `la lista en disco sirve sin red al volver a abrir la app`() = runBlocking {
        val blobs = InMemoryBlobStore()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        assertEquals(null, TablesRepository(api, blobs).cached("b1"))
        server.enqueue(MockResponse().setBody(page(listOf(table(1)), 1, 1)))
        TablesRepository(api, blobs).list("b1")
        assertEquals(1, TablesRepository(api, blobs).cached("b1")?.single()?.number)
    }

    @Test
    fun `cada estado del contrato tiene su texto`() {
        val expected = mapOf(
            "LIBRE" to R.string.table_free,
            "POSIBLE_OCUPACION" to R.string.table_possibly_occupied,
            "OCUPADA_SIN_COMANDA" to R.string.table_no_order,
            "ESPERANDO_COMIDA" to R.string.table_waiting_food,
            "CONSUMIENDO" to R.string.table_eating,
            "CUENTA_SOLICITADA" to R.string.table_check_requested,
            "POR_LIMPIAR" to R.string.table_to_clean,
        )
        expected.forEach { (state, label) ->
            assertEquals(label, tableStateLabel(TableDto("t", 1, 4, null, "ACTIVE", state)))
        }
        assertEquals(R.string.table_inactive, tableStateLabel(TableDto("t", 1, 4, null, "INACTIVE", null)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TablesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = ArrayDeque<ApiResult<List<TableDto>>>()
    private var calls = 0

    private fun viewModel() = TablesViewModel(
        object : TablesRepositoryFake() {
            override suspend fun next(): ApiResult<List<TableDto>> {
                calls++
                return results.removeFirstOrNull() ?: ApiResult.Success(emptyList())
            }
        }.repository(),
        "b1",
        pollMillis = 1000,
    )

    private abstract class TablesRepositoryFake {
        abstract suspend fun next(): ApiResult<List<TableDto>>

        fun repository(): TablesRepository {
            val outer = this
            return TablesRepository(object : SessionApi {
                override suspend fun me() = error("sin uso")
                override suspend fun logout() = error("sin uso")
                override suspend fun getMenu(branchId: String, etag: String?) = error("sin uso")
                override suspend fun createOrder(branchId: String, idempotencyKey: String, body: com.restia.mobile.core.network.OrderCreateRequest) = error("sin uso")
                override suspend fun listOrders(branchId: String, sessionId: String, page: Int, perPage: Int, etag: String?) = error("sin uso")
                override suspend fun transitionUnits(branchId: String, idempotencyKey: String, body: com.restia.mobile.core.network.UnitTransitionRequest) = error("sin uso")
                override suspend fun listTables(branchId: String, page: Int, perPage: Int) =
                    when (val r = outer.next()) {
                        is ApiResult.Success -> com.restia.mobile.core.network.PagedEnvelope(r.value)
                        is ApiResult.Failure -> throw java.io.IOException("sin red")
                    }

                override suspend fun openTableSession(branchId: String, idempotencyKey: String, body: com.restia.mobile.core.network.TableSessionOpenRequest) =
                    error("sin uso")
            })
        }
    }

    private val mesa = TableDto("t1", 1, 4, null, "ACTIVE", "LIBRE")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `consulta periodicamente y se detiene`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startPolling()
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(2, calls)
        vm.stopPolling()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `iniciar dos veces no duplica la consulta`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startPolling()
        vm.startPolling()
        runCurrent()
        assertEquals(1, calls)
        vm.stopPolling()
    }

    @Test
    fun `una falla conserva la lista y la marca, y el siguiente exito la limpia`() = runTest(dispatcher) {
        results += ApiResult.Success(listOf(mesa))
        results += ApiResult.Failure(ApiFailure.Network)
        results += ApiResult.Success(listOf(mesa))
        val vm = viewModel()
        vm.startPolling()
        runCurrent()
        assertEquals(1, vm.state.value.tables.size)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(1, vm.state.value.tables.size)
        assertNotNull(vm.state.value.failure)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(null, vm.state.value.failure)
        vm.stopPolling()
    }
}
