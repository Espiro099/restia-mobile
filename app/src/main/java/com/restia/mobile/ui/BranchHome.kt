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
import com.restia.mobile.ui.tables.TablesScreen
import com.restia.mobile.ui.tables.TablesViewModel

/** Mesas y comanda. Una navegación simple basta mientras sean dos pantallas. */
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
    // La clave incluye al usuario: otro inicio de sesión empieza sin la lista anterior.
    val tables: TablesViewModel = viewModel(
        key = "mesas-$userId-$branchId",
        factory = TablesViewModel.Factory(repositories.tables, branchId),
    )
    val current = sessionId
    if (current == null) {
        TablesScreen(
            viewModel = tables,
            branchName = branchName,
            role = role,
            canOpenTables = "table_sessions.open" in permissions,
            canOrder = "orders.create" in permissions,
            logoutEnabled = logoutEnabled,
            onLogout = onLogout,
            onOrder = { session, number ->
                sessionId = session
                tableNumber = number
            },
        )
    } else {
        OrderScreen(
            // La comanda en captura sobrevive a ir y volver de la lista de mesas.
            viewModel = viewModel(
                key = "comanda-$userId-$current",
                factory = OrderViewModel.Factory(repositories.menu, repositories.orders, branchId, current),
            ),
            tableNumber = tableNumber,
            onBack = { sessionId = null },
            onSent = {
                tables.notifyOrderSent(tableNumber)
                sessionId = null
            },
        )
    }
}
