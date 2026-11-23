package com.restia.mobile

import com.restia.mobile.ui.homeTabs
import com.restia.mobile.ui.more.HomeTab
import com.restia.mobile.ui.startTab
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabsTest {
    // Permisos de piso de cada rol, tal como los da config/permissions.php en restia-api.
    private val kitchen = listOf("orders.read", "order_units.prepare", "order_units.reassign", "tables.read", "table_sessions.read", "inventory.read", "printing.read")
    private val waiter = listOf("orders.read", "orders.create", "order_units.deliver", "tables.read", "table_sessions.open", "reservations.read", "printing.read")
    private val manager = waiter + listOf("order_units.prepare", "cash.read")

    @Test
    fun `cocina ve su cola, Inventario y Mas, y empieza en la cola`() {
        val tabs = homeTabs("KITCHEN", kitchen)
        assertEquals(listOf(HomeTab.KITCHEN, HomeTab.INVENTORY, HomeTab.MORE), tabs)
        assertEquals(HomeTab.KITCHEN, startTab(tabs))
    }

    @Test
    fun `mesero ve Mesas, Comandas, Entregas y Mas, y empieza en Mesas`() {
        val tabs = homeTabs("WAITER", waiter)
        assertEquals(listOf(HomeTab.TABLES, HomeTab.ORDERS, HomeTab.DELIVERIES, HomeTab.MORE), tabs)
        assertEquals(HomeTab.TABLES, startTab(tabs))
    }

    @Test
    fun `gerente ve todo lo del piso`() {
        val tabs = homeTabs("MANAGER", manager)
        assertEquals(listOf(HomeTab.TABLES, HomeTab.ORDERS, HomeTab.KITCHEN, HomeTab.MORE), tabs)
        assertEquals(HomeTab.TABLES, startTab(tabs))
    }

    @Test
    fun `sin permisos de piso solo queda Mas`() {
        assertEquals(listOf(HomeTab.MORE), homeTabs("KITCHEN", emptyList()))
        assertEquals(listOf(HomeTab.MORE), homeTabs("WAITER", listOf("menu.read")))
    }
}
