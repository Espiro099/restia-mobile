package com.restia.mobile

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.core.network.CustomerSearchRequest
import com.restia.mobile.core.network.CustomerUpdateRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.customers.CustomersRepository
import com.restia.mobile.data.reservations.ReservationsRepository
import com.restia.mobile.data.tables.TablesRepository
import com.restia.mobile.ui.customers.CustomerForm
import com.restia.mobile.ui.customers.CustomersViewModel
import com.restia.mobile.ui.customers.parsePreferences
import com.restia.mobile.ui.customers.searchRequest
import com.restia.mobile.ui.reservations.ReservationsViewModel
import java.time.Instant
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

private fun customer(id: String = "c1", name: String = "Ana López", version: Int = 1, notes: String? = "Alérgica a la nuez") =
    """{"id":"$id","name":"$name","phone":"+523312345678","email":null,"anonymized":false,
    "profile":{"notes":${notes?.let { "\"$it\"" } ?: "null"},"preferences":["Terraza"],"visitCount":3,"lastVisitAt":"2026-11-01T20:00:00Z"},"version":$version}"""

private fun customers(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":50,"total":${rows.size},"lastPage":1}}}"""

class CustomersPureTest {
    @Test
    fun `un telefono completo busca por telefono y el resto por nombre`() {
        assertEquals(CustomerSearchRequest(phone = "+523312345678"), searchRequest(" +52 33 1234 5678 "))
        assertEquals(CustomerSearchRequest(name = "Ana"), searchRequest("Ana"))
        assertNull(searchRequest("A"))
        assertEquals(CustomerSearchRequest(name = "+52"), searchRequest("+52"))
    }

    @Test
    fun `las preferencias se separan por coma sin vacias ni repetidas`() {
        assertEquals(listOf("Terraza", "Sin picante"), parsePreferences(" Terraza, ,Sin picante,Terraza "))
    }

    @Test
    fun `al editar solo se manda lo que cambio`() {
        val c = ApiJson.decodeFromString(CustomerDto.serializer(), customer())
        val form = CustomerForm.of(c)
        assertFalse(form.canSubmit)
        assertEquals(CustomerUpdateRequest(notes = "Sin gluten"), form.copy(notes = "Sin gluten").changes())
        assertEquals(CustomerUpdateRequest(preferences = listOf("Terraza", "Ventana")), form.copy(preferences = "Terraza, Ventana").changes())
        // Notas vacías no se mandan: el contrato no las admite.
        assertEquals(CustomerUpdateRequest(), form.copy(notes = "  ").changes())
        assertFalse(form.copy(email = "no-es-correo").canSubmit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CustomersFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var keys = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "GET" && path.endsWith("/tables") -> MockResponse().setBody(customers())
                    request.method == "GET" && path.endsWith("/waiting-queue") -> MockResponse().setBody("""{"data":[]}""")
                    request.method == "GET" && path.endsWith("/reservations") -> MockResponse().setBody(customers())
                    else -> answers.removeFirst()
                }
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

    private fun sent(path: String) = requests.filter { it.first.requestUrl!!.encodedPath == path }

    @Test
    fun `busca por telefono con POST y abre el cliente`() {
        val vm = CustomersViewModel(CustomersRepository(api), "b1", newKey = { "clave-${++keys}" })
        vm.setQuery("+523312345678")
        answers += MockResponse().setBody(customers(customer()))
        vm.search()
        waitFor { vm.state.value.searched }
        val (request, body) = sent("/branches/b1/customers/search").single()
        assertEquals("POST", request.method)
        assertEquals("""{"phone":"+523312345678"}""", body)
        // El teléfono va en el cuerpo, nunca en la URL (CU-3).
        assertFalse(request.path!!.contains("3312345678"))
        vm.select(vm.state.value.results.single())
        assertEquals(3, vm.state.value.selected!!.profile.visitCount)
    }

    @Test
    fun `alta con reintento sin respuesta no duplica`() {
        val vm = CustomersViewModel(CustomersRepository(api), "b1", newKey = { "clave-${++keys}" })
        vm.setQuery("+523312345678")
        vm.startCreate()
        assertEquals("+523312345678", vm.state.value.form!!.phone)
        vm.setName("Ana López")
        vm.setPreferences("Terraza")
        answers += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${customer()}}""")
        vm.submit()
        waitFor { vm.state.value.form?.failure != null }
        vm.submit()
        waitFor { vm.state.value.saved }
        val creates = sent("/branches/b1/customers")
        assertEquals(2, creates.size)
        assertEquals(creates[0].first.getHeader("Idempotency-Key"), creates[1].first.getHeader("Idempotency-Key"))
        assertEquals("""{"name":"Ana López","phone":"+523312345678","preferences":["Terraza"]}""", creates[0].second)
    }

    @Test
    fun `editar manda If-Match y solo lo cambiado`() {
        val vm = CustomersViewModel(CustomersRepository(api), "b1")
        answers += MockResponse().setBody("""{"data":${customer(version = 4)}}""")
        vm.open("c1")
        waitFor { vm.state.value.selected != null }
        vm.startEdit()
        vm.setNotes("Sin gluten")
        answers += MockResponse().setBody("""{"data":${customer(version = 5, notes = "Sin gluten")}}""")
        vm.submit()
        waitFor { vm.state.value.saved }
        val (request, body) = sent("/branches/b1/customers/c1").last()
        assertEquals("PATCH", request.method)
        assertEquals("\"4\"", request.getHeader("If-Match"))
        assertEquals("""{"notes":"Sin gluten"}""", body)
        assertEquals("Sin gluten", vm.state.value.selected!!.profile.notes)
    }

    private fun reservationsVm() = ReservationsViewModel(
        ReservationsRepository(api), TablesRepository(api), "b1", ZoneId.of("America/Mexico_City"), { Instant.parse("2026-11-20T16:00:00Z") },
        pollMillis = 3_600_000, newKey = { "clave-${++keys}" }, customers = CustomersRepository(api), canRegisterCustomers = true,
    )

    private val reservation = """{"data":{"id":"r1","status":"PENDING","startAt":"2026-11-20T20:00:00Z","endAt":"2026-11-20T21:30:00Z","partySize":2,
        "customer":{"customerId":"c1","name":"Ana López","phone":"+523312345678"},"confirmationCode":"ABCD2345","version":1}}"""

    @Test
    fun `al reservar con un telefono de cliente se liga y muestra sus notas`() {
        val vm = reservationsVm()
        vm.startCreate()
        vm.setPhone("+523312345678")
        answers += MockResponse().setBody(customers(customer()))
        vm.lookupCustomer()
        waitFor { vm.state.value.creating?.customer != null }
        assertEquals("Ana López", vm.state.value.creating!!.name)
        assertEquals("Alérgica a la nuez", vm.state.value.creating!!.customer!!.profile.notes)
        vm.setConfirmNow(false)
        answers += MockResponse().setResponseCode(201).setBody(reservation)
        vm.confirmCreate()
        waitFor { vm.state.value.creating == null }
        assertTrue(sent("/branches/b1/reservations").single { it.first.method == "POST" }.second.contains("\"customerId\":\"c1\""))
        assertTrue(sent("/branches/b1/customers").isEmpty())
    }

    @Test
    fun `si no es cliente lo registra antes de reservar`() {
        val vm = reservationsVm()
        vm.startCreate()
        vm.setName("Beto Ruiz")
        vm.setPhone("+523398765432")
        vm.setConfirmNow(false)
        answers += MockResponse().setBody(customers())
        answers += MockResponse().setResponseCode(201).setBody("""{"data":${customer(id = "c2", name = "Beto Ruiz")}}""")
        answers += MockResponse().setResponseCode(201).setBody(reservation)
        vm.confirmCreate()
        waitFor { vm.state.value.creating == null }
        assertEquals("""{"name":"Beto Ruiz","phone":"+523398765432"}""", sent("/branches/b1/customers").single().second)
        assertTrue(sent("/branches/b1/reservations").single { it.first.method == "POST" }.second.contains("\"customerId\":\"c2\""))
    }

    @Test
    fun `sin registrar la reserva va sin cliente`() {
        val vm = reservationsVm()
        vm.startCreate()
        vm.setName("Carla")
        vm.setPhone("+523355554444")
        vm.setRegisterCustomer(false)
        vm.setConfirmNow(false)
        answers += MockResponse().setResponseCode(201).setBody(reservation)
        vm.confirmCreate()
        waitFor { vm.state.value.creating == null }
        assertTrue(requests.none { it.first.requestUrl!!.encodedPath.contains("/customers") })
        assertFalse(sent("/branches/b1/reservations").single { it.first.method == "POST" }.second.contains("customerId"))
    }
}
