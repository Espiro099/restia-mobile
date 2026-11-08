package com.restia.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.restia.mobile.Repositories
import com.restia.mobile.ui.orders.OrderScreen
import com.restia.mobile.ui.orders.OrderViewModel
import com.restia.mobile.ui.orders.TableScreen
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.tables.TablesScreen
import com.restia.mobile.ui.tables.TablesViewModel

/** Mesas, vista de una mesa y comanda. Una navegación simple basta por ahora. */
@Composable
fun BranchHome(
    repositories: Repositories,
    userId: String,
    branchId: String,
    branchName: String,
    role: String,
    permissions: List<String>,
    logoutEnabled: Boolean,
    onLogout: () -> Unit,
) {
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var tableNumber by rememberSaveable { mutableStateOf(0) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    val canOrder = "orders.create" in permissions
    // La clave incluye al usuario: otro inicio de sesión empieza sin la lista anterior.
    val tables: TablesViewModel = viewModel(
        key = "mesas-$userId-$branchId",
        factory = TablesViewModel.Factory(repositories.tables, branchId, repositories.drafts, userId),
    )
    val current = sessionId
    if (current == null) {
        TablesScreen(
            viewModel = tables,
            branchName = branchName,
            role = role,
            canOpenTables = "table_sessions.open" in permissions,
            canOrder = canOrder,
            canViewOrders = "orders.read" in permissions,
            logoutEnabled = logoutEnabled,
            onLogout = onLogout,
            onViewTable = { session, number ->
                sessionId = session
                tableNumber = number
                capturing = false
            },
            onOrder = { session, number ->
                sessionId = session
                tableNumber = number
                capturing = canOrder
            },
        )
        return
    }
    val table: TableViewModel = viewModel(
        key = "mesa-$userId-$current",
        factory = TableViewModel.Factory(repositories.orders, branchId, current),
    )
    if (capturing) {
        OrderScreen(
            // La comanda en captura sobrevive a ir y volver de la lista de mesas.
            viewModel = viewModel(
                key = "comanda-$userId-$current",
                factory = OrderViewModel.Factory(
                    repositories.menu,
                    repositories.orders,
                    repositories.drafts,
                    repositories.contact,
                    branchId,
                    userId,
                    current,
                    tableNumber,
                ),
            ),
            tableNumber = tableNumber,
            onBack = { capturing = false },
            onSent = {
                table.notifySent()
                capturing = false
            },
        )
    } else {
        TableScreen(
            viewModel = table,
            tableNumber = tableNumber,
            canOrder = canOrder,
            canDeliver = "order_units.deliver" in permissions,
            onBack = { sessionId = null },
            onAddOrder = { capturing = true },
        )
    }
}
