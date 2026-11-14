package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MemberDto
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.account.PasswordRepository
import com.restia.mobile.data.members.MembersRepository
import com.restia.mobile.ui.account.ChangePasswordViewModel
import com.restia.mobile.ui.account.ForgotPasswordViewModel
import com.restia.mobile.ui.account.PasswordProblem
import com.restia.mobile.ui.account.passwordProblem
import com.restia.mobile.ui.members.MembersViewModel
import com.restia.mobile.ui.members.canManage
import com.restia.mobile.ui.members.manageableRoles
import com.restia.mobile.ui.members.sortMembers
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun member(id: String, name: String, role: String = "WAITER", active: Boolean = true, status: String = "ACTIVE", version: Int = 1) =
    """{"user":{"id":"$id","email":"$id@demo.mx","name":"$name","status":"$status","version":1},"branchId":"b1","role":"$role","active":$active,"version":$version}"""

private fun members(vararg rows: String) = """{"data":[${rows.joinToString()}],"meta":{"pagination":{"page":1,"perPage":100,"total":${rows.size},"lastPage":1}}}"""

private fun dto(json: String) = ApiJson.decodeFromString(MemberDto.serializer(), json)

class MembersPureTest {
    @Test
    fun `el gerente solo maneja meseros y cocina`() {
        assertEquals(listOf("ADMIN", "MANAGER", "WAITER", "KITCHEN"), manageableRoles("ADMIN"))
        assertEquals(listOf("WAITER", "KITCHEN"), manageableRoles("MANAGER"))
        assertEquals(emptyList<String>(), manageableRoles("WAITER"))
        assertTrue(canManage("MANAGER", "yo", dto(member("u1", "Ana"))))
        assertFalse(canManage("MANAGER", "yo", dto(member("u1", "Ana", role = "MANAGER"))))
        assertTrue(canManage("ADMIN", "yo", dto(member("u1", "Ana", role = "MANAGER"))))
        // Nadie se cambia a sí mismo, y un dado de baja no tiene acciones.
        assertFalse(canManage("ADMIN", "u1", dto(member("u1", "Ana"))))
        assertFalse(canManage("ADMIN", "yo", dto(member("u1", "Ana", active = false))))
    }

    @Test
    fun `activos primero y por nombre`() {
        val sorted = sortMembers(listOf(dto(member("1", "zoe")), dto(member("2", "Beto", active = false)), dto(member("3", "Ana"))))
        assertEquals(listOf("Ana", "zoe", "Beto"), sorted.map { it.user.name })
    }

    @Test
    fun `revisa la contrasena nueva antes de enviar`() {
        assertEquals(PasswordProblem.TOO_SHORT, passwordProblem("actual", "corta", "corta"))
        assertEquals(PasswordProblem.MISMATCH, passwordProblem("actual", "una frase larga", "una frase largo"))
        assertEquals(PasswordProblem.SAME_AS_CURRENT, passwordProblem("una frase larga", "una frase larga", "una frase larga"))
        assertNull(passwordProblem("actual", "una frase larga", "una frase larga"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MembersFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private lateinit var publicApi: PublicAuthApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var list = members(member("yo", "Gerente", role = "MANAGER"), member("u1", "Ana"), member("u2", "Beto", active = false))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return if (request.method == "GET" && path == "/branches/b1/members") MockResponse().setBody(list) else answers.removeFirst()
            }
        }
        server.start()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
        api = retrofit.create(SessionApi::class.java)
        publicApi = retrofit.create(PublicAuthApi::class.java)
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

    private fun loaded(role: String = "MANAGER"): MembersViewModel {
        val vm = MembersViewModel(MembersRepository(api), "b1", "yo", role)
        waitFor { vm.state.value.loaded }
        return vm
    }

    @Test
    fun `lista el personal y oculta a los dados de baja`() {
        val vm = loaded()
        assertEquals("100", sent("/branches/b1/members").single().first.requestUrl!!.queryParameter("perPage"))
        assertEquals(listOf("Ana", "Gerente"), vm.state.value.visible.map { it.user.name })
        assertEquals(1, vm.state.value.revokedCount)
        vm.toggleRevoked()
        assertEquals(listOf("Ana", "Gerente", "Beto"), vm.state.value.visible.map { it.user.name })
    }

    @Test
    fun `el alta muestra el enlace una vez y agrega al empleado`() {
        val vm = loaded()
        vm.startAdd()
        assertEquals("WAITER", vm.state.value.adding!!.role)
        vm.editAdd { it.copy(name = " Carla Ruiz ", email = "carla@demo.mx ", role = "KITCHEN") }
        answers += MockResponse().setResponseCode(201).setBody(
            """{"data":{"member":${member("u3", "Carla Ruiz", role = "KITCHEN", status = "INVITED")},
            "setupUrl":"https://restia.mx/contrasena#tok","setupExpiresAt":"2026-11-16T18:00:00Z"}}""",
        )
        vm.submitAdd()
        waitFor { vm.state.value.link != null }
        assertEquals("""{"email":"carla@demo.mx","name":"Carla Ruiz","role":"KITCHEN"}""", sent("/branches/b1/members").last { it.first.method == "POST" }.second)
        assertEquals("https://restia.mx/contrasena#tok", vm.state.value.link!!.url)
        assertNull(vm.state.value.adding)
        assertTrue(vm.state.value.members.any { it.user.id == "u3" && it.user.status == "INVITED" })
        vm.dismissLink()
        assertNull(vm.state.value.link)
    }

    @Test
    fun `si ya tenia cuenta no hay enlace`() {
        val vm = loaded()
        vm.startAdd()
        vm.editAdd { it.copy(name = "Dani", email = "dani@demo.mx") }
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"member":${member("u4", "Dani")}}}""")
        vm.submitAdd()
        waitFor { vm.state.value.link != null }
        assertNull(vm.state.value.link!!.url)
    }

    @Test
    fun `el alta rechazada conserva el formulario`() {
        val vm = loaded()
        vm.startAdd()
        vm.editAdd { it.copy(name = "Ana", email = "u1@demo.mx") }
        answers += MockResponse().setResponseCode(409)
            .setBody("""{"error":{"code":"MEMBERSHIP_ALREADY_EXISTS","message":"El empleado ya pertenece a esta sucursal."}}""")
        vm.submitAdd()
        waitFor { vm.state.value.adding?.failure != null }
        assertEquals("MEMBERSHIP_ALREADY_EXISTS", (vm.state.value.adding!!.failure as ApiFailure.Server).code)
        assertEquals("Ana", vm.state.value.adding!!.name)
    }

    @Test
    fun `un gerente no puede dar de alta a otro gerente`() {
        val vm = loaded()
        vm.startAdd()
        vm.editAdd { it.copy(name = "Eva", email = "eva@demo.mx", role = "MANAGER") }
        vm.submitAdd()
        assertTrue(sent("/branches/b1/members").none { it.first.method == "POST" })
    }

    @Test
    fun `cambiar rol manda If-Match`() {
        val vm = loaded()
        vm.select(vm.state.value.members.first { it.user.id == "u1" })
        vm.chooseRole("KITCHEN")
        answers += MockResponse().setBody("""{"data":${member("u1", "Ana", role = "KITCHEN", version = 2)}}""")
        vm.saveRole()
        waitFor { vm.state.value.selected == null }
        val (request, body) = sent("/branches/b1/members/u1").single()
        assertEquals("PATCH", request.method)
        assertEquals("\"1\"", request.getHeader("If-Match"))
        assertEquals("""{"role":"KITCHEN"}""", body)
        assertEquals("KITCHEN", vm.state.value.members.first { it.user.id == "u1" }.role)
    }

    @Test
    fun `tras un conflicto de version recarga y el reintento usa la nueva`() {
        val vm = loaded()
        vm.select(vm.state.value.members.first { it.user.id == "u1" })
        vm.startRevoke()
        vm.setReason("Renunció")
        list = members(member("yo", "Gerente", role = "MANAGER"), member("u1", "Ana", version = 5))
        answers += MockResponse().setResponseCode(409).setBody("""{"error":{"code":"VERSION_CONFLICT","message":"Cambió."}}""")
        vm.confirmRevoke()
        waitFor { vm.state.value.selected?.member?.version == 5 }
        assertEquals("VERSION_CONFLICT", (vm.state.value.selected!!.failure as ApiFailure.Server).code)
        answers += MockResponse().setBody("""{"data":${member("u1", "Ana", active = false, version = 6)}}""")
        vm.confirmRevoke()
        waitFor { vm.state.value.selected == null }
        val revokes = sent("/branches/b1/members/u1/revoke")
        assertEquals(listOf("\"1\"", "\"5\""), revokes.map { it.first.getHeader("If-Match") })
        assertEquals("""{"reason":"Renunció"}""", revokes.last().second)
        assertFalse(vm.state.value.members.first { it.user.id == "u1" }.active)
    }

    @Test
    fun `un enlace nuevo reemplaza al seleccionado`() {
        list = members(member("yo", "Admin", role = "ADMIN"), member("u5", "Fer", status = "INVITED"))
        val vm = loaded("ADMIN")
        vm.select(vm.state.value.members.first { it.user.id == "u5" })
        answers += MockResponse().setResponseCode(201).setBody("""{"data":{"setupUrl":"https://restia.mx/contrasena#nuevo","expiresAt":"2026-11-16T18:00:00Z"}}""")
        vm.newSetupLink()
        waitFor { vm.state.value.link != null }
        assertEquals("POST", sent("/users/u5/password-setup-link").single().first.method)
        assertEquals("https://restia.mx/contrasena#nuevo", vm.state.value.link!!.url)
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `cambiar la contrasena muestra el error del campo y luego borra lo escrito`() {
        val vm = ChangePasswordViewModel(PasswordRepository(publicApi, api))
        vm.edit { it.copy(current = "mala", new = "una frase larga", confirm = "una frase larga") }
        answers += MockResponse().setResponseCode(422).setBody(
            """{"error":{"code":"VALIDATION_FAILED","message":"Datos inválidos.","details":{"fields":{"currentPassword":["La contraseña actual no es correcta."]}}}}""",
        )
        vm.submit()
        waitFor { vm.state.value.failure != null }
        assertEquals(422, (vm.state.value.failure as ApiFailure.Server).status)
        vm.edit { it.copy(current = "la buena") }
        answers += MockResponse().setResponseCode(204)
        vm.submit()
        waitFor { vm.state.value.done }
        assertEquals("""{"currentPassword":"la buena","newPassword":"una frase larga"}""", sent("/auth/password/change").last().second)
        assertEquals("", vm.state.value.new)
        assertEquals("", vm.state.value.current)
    }

    @Test
    fun `olvide mi contrasena siempre confirma igual`() {
        val vm = ForgotPasswordViewModel(PasswordRepository(publicApi, api))
        vm.open(" ana@demo.mx ")
        assertEquals("ana@demo.mx", vm.state.value.email)
        answers += MockResponse().setResponseCode(204)
        vm.submit()
        waitFor { vm.state.value.sent }
        assertEquals("""{"email":"ana@demo.mx"}""", sent("/auth/password/forgot").single().second)
    }

    @Test
    fun `un 429 al pedir el enlace se informa`() = runBlocking {
        answers += MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
            .setBody("""{"error":{"code":"RATE_LIMITED","message":"Demasiados intentos."}}""")
        val r = PasswordRepository(publicApi, api).forgot("ana@demo.mx")
        val failure = (r as ApiResult.Failure).failure as ApiFailure.Server
        assertEquals(429, failure.status)
        assertEquals(60L, failure.retryAfterSeconds)
    }
}
