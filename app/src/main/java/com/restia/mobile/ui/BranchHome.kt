package com.restia.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.restia.mobile.Repositories
import com.restia.mobile.ui.checks.CheckPermissions
import com.restia.mobile.ui.checks.CheckScreen
import com.restia.mobile.ui.checks.CheckViewModel
import com.restia.mobile.ui.kitchen.KitchenScreen
import com.restia.mobile.ui.kitchen.KitchenViewModel
import com.restia.mobile.ui.orders.OrderScreen
import com.restia.mobile.ui.orders.OrderViewModel
import com.restia.mobile.ui.orders.TableScreen
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.tables.TablesScreen
import com.restia.mobile.ui.tables.TablesViewModel

/** Mesas, vista de una mesa, comanda y cola de cocina. Una navegación simple basta por ahora. */
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
    var viewingCheck by rememberSaveable { mutableStateOf(false) }
    val canOrder = "orders.create" in permissions
    val canPrepare = "order_units.prepare" in permissions
    // Quien prepara pero no toma comandas (cocina) empieza en la cola; el resto, en las mesas.
    var inKitchen by rememberSaveable { mutableStateOf(canPrepare && !canOrder) }
    if (inKitchen && "orders.read" in permissions) {
        KitchenScreen(
            viewModel = viewModel(key = "cocina-$userId-$branchId", factory = KitchenViewModel.Factory(repositories.kitchen, branchId)),
            branchName = branchName,
            canPrepare = canPrepare,
            showTables = "tables.read" in permissions,
            logoutEnabled = logoutEnabled,
            onTables = { inKitchen = false },
            onLogout = onLogout,
        )
        return
    }
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
            canClean = "tables.clean" in permissions,
            showKitchen = "orders.read" in permissions && (canPrepare || "order_units.deliver" in permissions),
            logoutEnabled = logoutEnabled,
            onLogout = onLogout,
            onViewTable = { session, number ->
                sessionId = session
                tableNumber = number
                capturing = false
                viewingCheck = false
            },
            onOrder = { session, number ->
                sessionId = session
                tableNumber = number
                capturing = canOrder
                viewingCheck = false
            },
            onKitchen = { inKitchen = true },
        )
        return
    }
    val table: TableViewModel = viewModel(
        key = "mesa-$userId-$current",
        factory = TableViewModel.Factory(
            repositories.orders,
            branchId,
            current,
            canVoidDirectly = "order_units.void" in permissions,
            sessions = repositories.tables,
        ),
    )
    if (viewingCheck) {
        CheckScreen(
            viewModel = viewModel(key = "cuenta-$userId-$current", factory = CheckViewModel.Factory(repositories.checks, branchId, current)),
            tableNumber = tableNumber,
            can = CheckPermissions(
                request = "checks.request" in permissions,
                // Cobrar exige elegir un turno abierto: sin poder verlos, no hay dónde registrar el pago.
                pay = "payments.create" in permissions && ("cash.read_open" in permissions || "cash.read" in permissions),
                record = "payments.record_result" in permissions,
                print = "printing.request" in permissions,
            ),
            onBack = { viewingCheck = false },
        )
        return
    }
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
            canCancel = "order_units.cancel" in permissions,
            // Sin el permiso directo, igual puede anular con la autorización de un gerente (D-37).
            canVoid = "order_units.void" in permissions || "order_units.cancel" in permissions,
            canClose = "table_sessions.close" in permissions,
            canViewCheck = "checks.read" in permissions,
            onBack = { sessionId = null },
            onAddOrder = { capturing = true },
            onCheck = { viewingCheck = true },
        )
    }
}
