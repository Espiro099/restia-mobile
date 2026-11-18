package com.restia.mobile

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.DailySalesDto
import com.restia.mobile.core.network.DailySalesMethodDto
import com.restia.mobile.core.network.HourlyOccupancyDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.data.reports.ReportsRepository
import com.restia.mobile.ui.reports.ReportPeriod
import com.restia.mobile.ui.reports.ReportsViewModel
import com.restia.mobile.ui.reports.periodRange
import com.restia.mobile.ui.reports.summarize
import java.math.BigDecimal
import java.time.LocalDate
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

private fun mxn(amount: String) = MoneyDto(amount, "MXN")

private fun day(date: String, net: String, checks: Int, guests: Int, vararg methods: Pair<String, String>) = DailySalesDto(
    businessDate = date, netSales = mxn(net), refunds = mxn("0.00"), tips = mxn("10.00"), courtesies = mxn("0.00"), invoiced = mxn("0.00"),
    checkCount = checks, guestCount = guests,
    byMethod = methods.map { (m, a) -> DailySalesMethodDto(m, mxn(a), mxn("0.00"), mxn("0.00")) },
    asOf = "2026-11-18T18:00:${date.takeLast(2)}Z",
)

private fun hour(date: String, h: Int, tables: Int, sessions: Int) = HourlyOccupancyDto(date, h, sessions, tables, sessions * 2, "2026-11-18T18:00:00Z")

class ReportsPureTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun `el periodo termina hoy e incluye los dos extremos`() {
        assertEquals(today to today, periodRange(ReportPeriod.TODAY, today))
        assertEquals(LocalDate.of(2026, 11, 12) to today, periodRange(ReportPeriod.WEEK, today))
        assertEquals(LocalDate.of(2026, 10, 20) to today, periodRange(ReportPeriod.MONTH, today))
    }

    @Test
    fun `suma el periodo, llena los dias sin venta y ordena los metodos`() {
        val (from, to) = periodRange(ReportPeriod.WEEK, today)
        val s = summarize(
            listOf(
                day("2026-11-16", "300.00", 2, 5, "CASH" to "100.00", "CARD_CREDIT" to "200.00"),
                day("2026-11-12", "100.00", 1, 2, "CASH" to "100.00"),
            ),
            emptyList(), from, to,
        )
        assertEquals(BigDecimal("400.00"), s.net)
        assertEquals(3, s.checks)
        assertEquals(7, s.guests)
        assertEquals(BigDecimal("133.33"), s.averageTicket)
        assertEquals(BigDecimal("20.00"), s.tips)
        assertEquals(7, s.days.size)
        assertEquals(BigDecimal.ZERO, s.days[1].net)
        assertEquals(BigDecimal("300.00"), s.days[4].net)
        // Efectivo 200 y crédito 200: empatan; cada uno es la mitad.
        assertEquals(setOf("CASH", "CARD_CREDIT"), s.byMethod.map { it.method }.toSet())
        assertEquals(0.5f, s.byMethod.first().share, 0.001f)
        assertEquals("2026-11-18T18:00:16Z", s.asOf)
    }

    @Test
    fun `la ocupacion es el promedio por dia, sin huecos entre la primera y la ultima hora`() {
        val (from, to) = periodRange(ReportPeriod.WEEK, today)
        val s = summarize(
            emptyList(),
            listOf(hour("2026-11-16", 13, 4, 3), hour("2026-11-17", 13, 3, 2), hour("2026-11-17", 15, 7, 5)),
            from, to,
        )
        assertEquals(listOf(13, 14, 15), s.hours.map { it.hour })
        // 7 mesas en 7 días: 1.0 por día a las 13 h; 7 en un día a las 15 h: 1.0 también.
        assertEquals(BigDecimal("1.0"), s.hours[0].tables)
        assertEquals(BigDecimal("0.0"), s.hours[1].tables)
        assertEquals(5, s.hours[0].sessions)
        assertEquals(13, s.peak!!.hour)
    }

    @Test
    fun `los montos llevan separador de miles`() {
        assertEquals("${'$'}13,611.00 MXN", com.restia.mobile.ui.reports.reportMoney(BigDecimal("13611"), "MXN"))
        assertEquals("${'$'}0.50 MXN", com.restia.mobile.ui.reports.reportMoney(BigDecimal("0.5"), "MXN"))
    }

    @Test
    fun `sin cuentas no hay ticket promedio y el periodo vacio se reconoce`() {
        val s = summarize(emptyList(), emptyList(), today, today)
        assertNull(s.averageTicket)
        assertTrue(s.empty)
        assertTrue(s.hours.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var vm: ReportsViewModel
    private val requests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.requestUrl!!.encodedPath) {
                    "/branches/b1/reports/daily-sales" -> MockResponse().setBody(
                        """{"data":[{"businessDate":"2026-11-18","netSales":{"amount":"207.00","currency":"MXN"},"refunds":{"amount":"0.00","currency":"MXN"},
                        "tips":{"amount":"20.00","currency":"MXN"},"courtesies":{"amount":"0.00","currency":"MXN"},"invoiced":{"amount":"0.00","currency":"MXN"},
                        "checkCount":1,"guestCount":2,"byMethod":[{"method":"CASH","sales":{"amount":"207.00","currency":"MXN"},"tips":{"amount":"20.00","currency":"MXN"},
                        "refunds":{"amount":"0.00","currency":"MXN"}}],"asOf":"2026-11-18T18:00:00Z"}],"meta":{}}""",
                    )
                    "/branches/b1/reports/hourly-occupancy" -> MockResponse().setBody(
                        """{"data":[{"businessDate":"2026-11-18","hour":14,"sessionsOpened":1,"tablesOccupied":1,"guestCount":2,"asOf":"2026-11-18T18:00:00Z"}],"meta":{}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        // 127.0.0.1: en Linux "localhost" también es ::1 (ver OpenTableTest).
        val api = Retrofit.Builder().baseUrl("http://127.0.0.1:${server.port}/")
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType())).build()
            .create(SessionApi::class.java)
        vm = ReportsViewModel(ReportsRepository(api), "b1") { LocalDate.of(2026, 11, 18) }
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

    @Test
    fun `pide hoy y luego 7 dias con el rango correcto`() {
        vm.load()
        waitFor { vm.state.value.summary != null }
        assertEquals(BigDecimal("207.00"), vm.state.value.summary!!.net)
        assertEquals(listOf("2026-11-18" to "2026-11-18", "2026-11-18" to "2026-11-18"), requests.map { it.requestUrl!!.queryParameter("from") to it.requestUrl!!.queryParameter("to") })
        requests.clear()
        vm.select(ReportPeriod.WEEK)
        waitFor { !vm.state.value.loading && vm.state.value.summary != null }
        assertEquals(ReportPeriod.WEEK, vm.state.value.period)
        assertEquals("2026-11-12", requests.first().requestUrl!!.queryParameter("from"))
        assertEquals(7, vm.state.value.summary!!.days.size)
    }
}
