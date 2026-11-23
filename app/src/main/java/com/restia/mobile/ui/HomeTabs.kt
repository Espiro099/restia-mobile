package com.restia.mobile.ui

import com.restia.mobile.ui.more.HomeTab

/**
 * Pestañas de la barra inferior según el rol, y dentro de él, según los permisos que da la API.
 *
 * - Cocina: solo su cola (Cocina) y Más. No ve mesas ni comandas del salón.
 * - Mesero: Mesas, Comandas, Entregas (la cola vista para llevar lo que ya está listo) y Más.
 * - Gerente y administrador: todo lo del piso, como hasta ahora.
 *
 * Un rol desconocido cae en las reglas por permisos, sin pestañas de más.
 */
fun homeTabs(role: String, permissions: Collection<String>): List<HomeTab> {
    val readsOrders = "orders.read" in permissions
    val prepares = "order_units.prepare" in permissions
    val delivers = "order_units.deliver" in permissions
    return buildList {
        when (role) {
            "KITCHEN" -> if (readsOrders && prepares) add(HomeTab.KITCHEN)
            "WAITER" -> {
                if ("tables.read" in permissions) add(HomeTab.TABLES)
                if (readsOrders) add(HomeTab.ORDERS)
                if (readsOrders && delivers) add(HomeTab.DELIVERIES)
            }
            else -> {
                if ("tables.read" in permissions) add(HomeTab.TABLES)
                if (readsOrders) add(HomeTab.ORDERS)
                if (readsOrders && prepares) {
                    add(HomeTab.KITCHEN)
                } else if (readsOrders && delivers) {
                    add(HomeTab.DELIVERIES)
                }
            }
        }
        add(HomeTab.MORE)
    }
}

/** Pestaña con la que abre la app: cocina empieza en su cola; el resto, en la primera (Mesas). */
fun startTab(tabs: List<HomeTab>): HomeTab =
    if (HomeTab.KITCHEN in tabs && HomeTab.TABLES !in tabs) HomeTab.KITCHEN else tabs.first()
