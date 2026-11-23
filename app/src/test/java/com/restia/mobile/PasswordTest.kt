package com.restia.mobile

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.PublicAuthApi
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.account.PasswordRepository
import com.restia.mobile.ui.account.ChangePasswordViewModel
import com.restia.mobile.ui.account.ForgotPasswordViewModel
import com.restia.mobile.ui.account.PasswordProblem
import com.restia.mobile.ui.account.isValidEmail
import com.restia.mobile.ui.account.passwordProblem
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

class PasswordPureTest {
    @Test
    fun `revisa la contrasena nueva antes de enviar`() {
        assertEquals(PasswordProblem.TOO_SHORT, passwordProblem("actual", "corta", "corta"))
        assertEquals(PasswordProblem.MISMATCH, passwordProblem("actual", "una frase larga", "una frase largo"))
        assertEquals(PasswordProblem.SAME_AS_CURRENT, passwordProblem("una frase larga", "una frase larga", "una frase larga"))
        assertNull(passwordProblem("actual", "una frase larga", "una frase larga"))
    }

    @Test
    fun `reconoce un correo antes de pedir el enlace`() {
        assertTrue(isValidEmail(" ana@demo.mx "))
        assertFalse(isValidEmail("ana@demo"))
        assertFalse(isValidEmail("ana demo@demo.mx"))
        assertFalse(isValidEmail(""))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionApi
    private lateinit var publicApi: PublicAuthApi
    private val answers = ArrayDeque<MockResponse>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                return answers.removeFirst()
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
