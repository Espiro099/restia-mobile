package com.restia.mobile

import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.QueueUnitDto
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.ui.orders.TableOrders
import com.restia.mobile.ui.orders.matchesSearch
import com.restia.mobile.ui.orders.minutesSince
import com.restia.mobile.ui.orders.tableOrders
import com.restia.mobile.ui.tables.TableLook
import com.restia.mobile.ui.tables.greetingFor
import com.restia.mobile.ui.tables.tableLook
import java.time.LocalTime
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lógica de las pantallas de la propuesta 04: comandas por mesa, estado de la tarjeta, saludo y búsqueda. */
class ProposalLogicTest {
    private var seq = 0

    private fun unit(table: Int?, product: String, status: String, at: String = "2026-11-17T12:00:00Z") = KitchenUnitDto(
        unit = QueueUnitDto("u${seq++}", "o-$table", "i-$table-$product", 1, status, "s1", at, 1),
        productName = product,
        tableNumbers = listOfNotNull(table),
    )

    @Test
    fun `la cola se agrupa por mesa y platillo, la que mas espera primero`() {
        val orders = tableOrders(
            listOf(
                unit(5, "Tacos", "PREPARING", "2026-11-17T12:10:00Z"),
                unit(5, "Tacos", "READY", "2026-11-17T12:10:00Z"),
                unit(5, "Agua", "PENDING", "2026-11-17T12:12:00Z"),
                unit(2, "Sopa", "READY", "2026-11-17T12:00:00Z"),
                unit(null, "Para llevar", "PENDING"),
            ),
        )
        assertEquals(listOf(2, 5), orders.map { it.tableNumber })
        val five = orders[1]
        assertEquals(3, five.total)
        assertEquals(1, five.ready)
        assertEquals(0, five.stage) // Agua sigue pendiente: la mesa va en "Recibida".
        assertEquals(2, orders[0].stage)
        assertEquals(listOf("Tacos", "Agua"), five.lines.map { it.name })
    }

    private fun table(state: String?, status: String = "ACTIVE") =
        TableDto(id = "t1", number = 1, capacity = 4, status = status, operationalState = state)

    @Test
    fun `una mesa con platillos listos se ve lista para servir`() {
        val ready = TableOrders(1, "2026-11-17T12:00:00Z", listOf(com.restia.mobile.ui.orders.ActiveLine("Sopa", 0, 0, 2)))
        val cooking = TableOrders(1, "2026-11-17T12:00:00Z", listOf(com.restia.mobile.ui.orders.ActiveLine("Sopa", 0, 1, 0)))
        assertEquals(TableLook.READY, tableLook(table("ESPERANDO_COMIDA"), ready))
        assertEquals(TableLook.READY, tableLook(table("CONSUMIENDO"), ready))
        assertEquals(TableLook.WAITING, tableLook(table("ESPERANDO_COMIDA"), cooking))
        assertEquals(TableLook.EATING, tableLook(table("CONSUMIENDO"), null))
    }

    @Test
    fun `cada estado de mesa tiene su tarjeta`() {
        assertEquals(TableLook.FREE, tableLook(table("LIBRE"), null))
        assertEquals(TableLook.POSSIBLE, tableLook(table("POSIBLE_OCUPACION"), null))
        assertEquals(TableLook.NO_ORDER, tableLook(table("OCUPADA_SIN_COMANDA"), null))
        assertEquals(TableLook.CHECK, tableLook(table("CUENTA_SOLICITADA"), null))
        assertEquals(TableLook.CLEANING, tableLook(table("POR_LIMPIAR"), null))
        assertEquals(TableLook.OFF, tableLook(table("LIBRE", status = "MAINTENANCE"), null))
    }

    @Test
    fun `el saludo depende de la hora`() {
        assertEquals(R.string.greeting_morning, greetingFor(LocalTime.of(9, 0)))
        assertEquals(R.string.greeting_afternoon, greetingFor(LocalTime.of(15, 30)))
        assertEquals(R.string.greeting_evening, greetingFor(LocalTime.of(21, 0)))
        assertEquals(R.string.greeting_evening, greetingFor(LocalTime.of(2, 0)))
    }

    @Test
    fun `los minutos de espera no son negativos y la hora rara no truena`() {
        val now = OffsetDateTime.parse("2026-11-17T12:30:00Z")
        assertEquals(30L, minutesSince("2026-11-17T12:00:00Z", now))
        assertEquals(0L, minutesSince("2026-11-17T12:45:00Z", now))
        assertNull(minutesSince("ayer", now))
    }

    private fun product(name: String, description: String? = null) = MenuProductDto(
        id = name, name = name, description = description, category = "C", displayOrder = 1,
        price = MoneyDto("10.00", "MXN"), availability = "AVAILABLE",
    )

    @Test
    fun `la busqueda ignora acentos y mayusculas y mira la descripcion`() {
        assertTrue(matchesSearch(product("Ensalada César"), "cesar"))
        assertTrue(matchesSearch(product("Tacos al pastor", "Piña, cilantro y cebolla"), "PIÑA"))
        assertTrue(matchesSearch(product("Tacos al pastor", "Piña"), "pina"))
        assertTrue(matchesSearch(product("Agua"), "  "))
        assertFalse(matchesSearch(product("Agua"), "sopa"))
    }
}
