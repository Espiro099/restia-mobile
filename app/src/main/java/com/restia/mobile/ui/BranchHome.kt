package com.restia.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.restia.mobile.R
import com.restia.mobile.Repositories
import com.restia.mobile.ui.account.ChangePasswordScreen
import com.restia.mobile.ui.account.ChangePasswordViewModel
import com.restia.mobile.ui.account.SessionsScreen
import com.restia.mobile.ui.account.SessionsViewModel
import com.restia.mobile.ui.devices.DevicePermissions
import com.restia.mobile.ui.devices.DevicesScreen
import com.restia.mobile.ui.devices.DevicesViewModel
import com.restia.mobile.ui.cash.CashPermissions
import com.restia.mobile.ui.cash.CashScreen
import com.restia.mobile.ui.cash.CashViewModel
import com.restia.mobile.ui.checks.CheckPermissions
import com.restia.mobile.ui.checks.CheckScreen
import com.restia.mobile.ui.checks.CheckViewModel
import com.restia.mobile.ui.customers.CustomersScreen
import com.restia.mobile.ui.customers.CustomersViewModel
import com.restia.mobile.ui.kitchen.AvailabilityViewModel
import com.restia.mobile.ui.kitchen.KitchenScreen
import com.restia.mobile.ui.kitchen.KitchenViewModel
import com.restia.mobile.ui.members.MembersScreen
import com.restia.mobile.ui.members.MembersViewModel
import com.restia.mobile.ui.more.HomeScaffold
import com.restia.mobile.ui.more.HomeTab
import com.restia.mobile.ui.more.MoreOption
import com.restia.mobile.ui.more.MoreScreen
import com.restia.mobile.ui.orders.ActiveOrdersScreen
import com.restia.mobile.ui.orders.ActiveOrdersViewModel
import com.restia.mobile.ui.orders.OrderScreen
import com.restia.mobile.ui.orders.OrderViewModel
import com.restia.mobile.ui.orders.TableScreen
import com.restia.mobile.ui.orders.SessionViewModel
import com.restia.mobile.ui.orders.TableViewModel
import com.restia.mobile.ui.printing.PrintJobsScreen
import com.restia.mobile.ui.printing.PrintJobsViewModel
import com.restia.mobile.ui.printing.PrintPermissions
import com.restia.mobile.ui.notifications.NotificationsScreen
import com.restia.mobile.ui.notifications.NotificationsViewModel
import com.restia.mobile.ui.reports.ReportsScreen
import com.restia.mobile.ui.reports.ReportsViewModel
import com.restia.mobile.ui.reservations.ReservationPermissions
import com.restia.mobile.ui.reservations.ReservationsScreen
import com.restia.mobile.ui.reservations.ReservationsViewModel
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
    userName: String = "",
    userEmail: String = "",
) {
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var tableNumber by rememberSaveable { mutableStateOf(0) }
    var capturing by rememberSaveable { mutableStateOf(false) }
    var viewingCheck by rememberSaveable { mutableStateOf(false) }
    var inCustomers by rememberSaveable { mutableStateOf(false) }
    // Cliente abierto desde una reserva; null para la búsqueda.
    var customerId by rememberSaveable { mutableStateOf<String?>(null) }
    var inStaff by rememberSaveable { mutableStateOf(false) }
    var changingPassword by rememberSaveable { mutableStateOf(false) }
    val showStaff = "members.read" in permissions
    var inCash by rememberSaveable { mutableStateOf(false) }
    var inReservations by rememberSaveable { mutableStateOf(false) }
    var inNotifications by rememberSaveable { mutableStateOf(false) }
    var inReports by rememberSaveable { mutableStateOf(false) }
    var inSessions by rememberSaveable { mutableStateOf(false) }
    var inDevices by rememberSaveable { mutableStateOf(false) }
    val showDevices = "devices.read" in permissions
    // Ver montos y movimientos exige cash.read; quien solo tiene cash.read_open (mesero) no entra.
    val showCash = "cash.read" in permissions
    var inPrinting by rememberSaveable { mutableStateOf(false) }
    val showPrinting = "printing.read" in permissions
    if (inPrinting && showPrinting) {
        PrintJobsScreen(
            viewModel = viewModel(
                key = "impresiones-$userId-$branchId",
                factory = PrintJobsViewModel.Factory(
                    repositories.printing,
                    branchId,
                    PrintPermissions(resolve = "printing.resolve" in permissions, reprint = "printing.reprint" in permissions),
                ),
            ),
            onBack = { inPrinting = false },
        )
        return
    }
    if (inReports && "reports.read" in permissions) {
        ReportsScreen(
            viewModel = viewModel(key = "reportes-$userId-$branchId", factory = ReportsViewModel.Factory(repositories.reports, branchId)),
            onBack = { inReports = false },
        )
        return
    }
    if (inDevices && showDevices) {
        DevicesScreen(
            viewModel = viewModel(
                key = "dispositivos-$userId-$branchId",
                factory = DevicesViewModel.Factory(
                    repositories.devices,
                    branchId,
                    DevicePermissions(enroll = "devices.enroll" in permissions, manage = "devices.manage" in permissions),
                ),
            ),
            onBack = { inDevices = false },
        )
        return
    }
    if (inSessions) {
        SessionsScreen(
            viewModel = viewModel(key = "sesiones-$userId", factory = SessionsViewModel.Factory(repositories.sessions)),
            onBack = { inSessions = false },
        )
        return
    }
    if (changingPassword) {
        val passwords: ChangePasswordViewModel = viewModel(key = "contrasena-$userId", factory = ChangePasswordViewModel.Factory(repositories.passwords))
        ChangePasswordScreen(
            viewModel = passwords,
            onBack = {
                // Al salir se borra lo escrito: la siguiente visita empieza en blanco.
                passwords.reset()
                changingPassword = false
            },
        )
        return
    }
    if (inCash && showCash) {
        CashScreen(
            viewModel = viewModel(
                key = "caja-$userId-$branchId",
                factory = CashViewModel.Factory(
                    repositories.cash,
                    branchId,
                    CashPermissions(
                        open = "cash.open" in permissions,
                        move = "cash.move" in permissions,
                        close = "cash.close" in permissions,
                        readPayments = "payments.read" in permissions,
                        reconcilePayments = "payments.reconcile" in permissions,
                        readRefunds = "refunds.read" in permissions,
                        refund = "refunds.create" in permissions,
                        refundResult = "refunds.record_result" in permissions,
                        reconcileRefunds = "refunds.reconcile" in permissions,
                    ),
                ),
            ),
            onBack = { inCash = false },
        )
        return
    }
    if (inStaff && showStaff) {
        MembersScreen(
            viewModel = viewModel(key = "personal-$userId-$branchId", factory = MembersViewModel.Factory(repositories.members, branchId, userId, role)),
            canResendLink = "users.manage" in permissions,
            onBack = { inStaff = false },
        )
        return
    }
    if (inCustomers && "customers.read" in permissions) {
        CustomersScreen(
            viewModel = viewModel(key = "clientes-$userId-$branchId-${customerId ?: "busqueda"}", factory = CustomersViewModel.Factory(repositories.customers, branchId)),
            canCreate = "customers.create" in permissions,
            canUpdate = "customers.update" in permissions,
            openCustomerId = customerId,
            onBack = {
                inCustomers = false
                customerId = null
            },
        )
        return
    }
    if (inReservations && "reservations.read" in permissions) {
        ReservationsScreen(
            viewModel = viewModel(
                key = "reservas-$userId-$branchId",
                factory = ReservationsViewModel.Factory(
                    repositories.reservations,
                    repositories.tables,
                    branchId,
                    repositories.customers,
                    canRegisterCustomers = "customers.create" in permissions,
                ),
            ),
            can = ReservationPermissions(
                create = "reservations.create" in permissions,
                readCustomers = "customers.read" in permissions,
                createCustomers = "customers.create" in permissions,
                confirm = "reservations.confirm" in permissions,
                arrive = "reservations.arrive" in permissions,
                seat = "reservations.check_in" in permissions,
                cancel = "reservations.cancel" in permissions,
                noShow = "reservations.mark_no_show" in permissions,
                update = "reservations.update" in permissions,
                overridePriority = "reservations.override_priority" in permissions,
            ),
            onBack = { inReservations = false },
            onCustomers = {
                customerId = null
                inCustomers = true
            },
            onCustomer = {
                customerId = it
                inCustomers = true
            },
            // Al sentar, la mesa se abre: se pasa directo a su vista.
            onSeated = { session, number ->
                inReservations = false
                sessionId = session
                tableNumber = number
                capturing = false
                viewingCheck = false
            },
        )
        return
    }
    val canOrder = "orders.create" in permissions
    val canPrepare = "order_units.prepare" in permissions
    // La clave incluye al usuario: otro inicio de sesión empieza sin la lista anterior.
    val tables: TablesViewModel = viewModel(
        key = "mesas-$userId-$branchId",
        factory = TablesViewModel.Factory(repositories.tables, branchId, repositories.drafts, userId),
    )
    val notices: NotificationsViewModel = viewModel(
        key = "avisos-$userId-$branchId",
        factory = NotificationsViewModel.Factory(repositories.notifications, branchId),
    )
    if (inNotifications && sessionId == null) {
        NotificationsScreen(
            viewModel = notices,
            onBack = { inNotifications = false },
            // Un aviso lleva a su mesa; si es de una cuenta o un pago, directo a la cuenta.
            onOpen = { notice ->
                val session = notice.sessionId
                if (session != null && "orders.read" in permissions) {
                    inNotifications = false
                    sessionId = session
                    tableNumber = notice.tableNumber
                    capturing = false
                    viewingCheck = notice.checkId != null && "checks.read" in permissions
                }
            },
        )
        return
    }
    val current = sessionId
    if (current == null) {
        // Secciones de la barra inferior, según los permisos; "Más" siempre está.
        val tabs = buildList {
            if ("tables.read" in permissions) add(HomeTab.TABLES)
            if ("orders.read" in permissions) add(HomeTab.ORDERS)
            if ("orders.read" in permissions && (canPrepare || "order_units.deliver" in permissions)) add(HomeTab.KITCHEN)
            add(HomeTab.MORE)
        }
        // Quien prepara pero no toma comandas (cocina) empieza en la cola; el resto, en las mesas.
        val start = if (canPrepare && !canOrder && HomeTab.KITCHEN in tabs) HomeTab.KITCHEN else tabs.first()
        var tabName by rememberSaveable { mutableStateOf(start.name) }
        val tab = HomeTab.entries.firstOrNull { it.name == tabName }?.takeIf { it in tabs } ?: start
        // La cola vista desde el salón: la pestaña Comandas y el aviso de "orden lista" en Mesas.
        val active: ActiveOrdersViewModel? = if ("orders.read" in permissions) {
            viewModel(key = "comandas-$userId-$branchId", factory = ActiveOrdersViewModel.Factory(repositories.kitchen, branchId))
        } else {
            null
        }
        val activeState = active?.state?.collectAsState()?.value
        val openTable: (Int) -> Unit = { number ->
            tables.state.value.tables.firstOrNull { it.number == number }?.currentSessionId?.let { session ->
                sessionId = session
                tableNumber = number
                capturing = false
                viewingCheck = false
            }
        }
        HomeScaffold(tabs, tab, onSelect = { tabName = it.name }) {
            when (tab) {
                HomeTab.TABLES -> {
                    // La cola se consulta mientras el plano está a la vista, para el aviso de "orden lista".
                    if (active != null) {
                        LifecycleStartEffect(active) {
                            active.startPolling()
                            onStopOrDispose { active.stopPolling() }
                        }
                    }
                    // Y los avisos, para la marca de la campana.
                    LifecycleStartEffect(notices) {
                        notices.startPolling()
                        onStopOrDispose { notices.stopPolling() }
                    }
                    val noticeState by notices.state.collectAsState()
                    TablesScreen(
                        viewModel = tables,
                        userName = userName,
                        branchName = branchName,
                        canOpenTables = "table_sessions.open" in permissions,
                        canOrder = canOrder,
                        canViewOrders = "orders.read" in permissions,
                        canClean = "tables.clean" in permissions,
                        activeOrders = activeState?.tables.orEmpty(),
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
                        unreadNotices = noticeState.unread,
                        onNotices = { inNotifications = true },
                    )
                }
                HomeTab.ORDERS -> if (active != null) {
                    ActiveOrdersScreen(
                        viewModel = active,
                        onOpenTable = openTable,
                        onGoToTables = if (HomeTab.TABLES in tabs) ({ tabName = HomeTab.TABLES.name }) else null,
                    )
                }
                HomeTab.KITCHEN -> KitchenScreen(
                    viewModel = viewModel(key = "cocina-$userId-$branchId", factory = KitchenViewModel.Factory(repositories.kitchen, branchId)),
                    branchName = branchName,
                    canPrepare = canPrepare,
                    showTables = false,
                    canReassign = "order_units.reassign" in permissions,
                    availability = if ("menu.set_availability" in permissions) {
                        viewModel(key = "agotados-$userId-$branchId", factory = AvailabilityViewModel.Factory(repositories.menu, branchId))
                    } else {
                        null
                    },
                    inTabs = true,
                    logoutEnabled = logoutEnabled,
                    onTables = {},
                    onLogout = onLogout,
                    showStaff = false,
                    onStaff = {},
                    onChangePassword = {},
                )
                HomeTab.MORE -> MoreScreen(
                    userName = userName,
                    userEmail = userEmail,
                    role = role,
                    branchName = branchName,
                    tools = buildList {
                        if ("reservations.read" in permissions) add(MoreOption(R.drawable.ic_calendar, R.string.reservations_title) { inReservations = true })
                        if (showCash) add(MoreOption(R.drawable.ic_card, R.string.cash_title) { inCash = true })
                        if ("reports.read" in permissions) add(MoreOption(R.drawable.ic_chart, R.string.reports_title) { inReports = true })
                        if (showPrinting) add(MoreOption(R.drawable.ic_receipt, R.string.print_title) { inPrinting = true })
                        if (showStaff) add(MoreOption(R.drawable.ic_guests, R.string.staff_title) { inStaff = true })
                        if (showDevices) add(MoreOption(R.drawable.ic_phone, R.string.devices_title) { inDevices = true })
                        if ("customers.read" in permissions) {
                            add(MoreOption(R.drawable.ic_person, R.string.customers_title) {
                                customerId = null
                                inCustomers = true
                            })
                        }
                    },
                    onChangePassword = { changingPassword = true },
                    onSessions = { inSessions = true },
                    logoutEnabled = logoutEnabled,
                    onLogout = onLogout,
                )
            }
        }
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
            viewModel = viewModel(
                key = "cuenta-$userId-$current",
                factory = CheckViewModel.Factory(
                    repositories.checks,
                    branchId,
                    current,
                    // Sin estos permisos, reabrir y las cortesías piden la autorización de un gerente (C-9).
                    canReopenDirectly = "checks.reopen" in permissions,
                    canAdjustDirectly = "checks.adjust" in permissions,
                ),
            ),
            tableNumber = tableNumber,
            can = CheckPermissions(
                request = "checks.request" in permissions,
                // Cobrar exige elegir un turno abierto: sin poder verlos, no hay dónde registrar el pago.
                pay = "payments.create" in permissions && ("cash.read_open" in permissions || "cash.read" in permissions),
                record = "payments.record_result" in permissions,
                print = "printing.request" in permissions,
                move = "checks.move_units" in permissions,
                create = "checks.create" in permissions,
                cancel = "checks.cancel" in permissions,
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
            canSetAvailability = "menu.set_availability" in permissions,
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
            canChangeGuests = "table_sessions.update" in permissions,
            onAddOrder = { capturing = true },
            onCheck = { viewingCheck = true },
            session = viewModel(key = "sesion-$userId-$current", factory = SessionViewModel.Factory(repositories.tables, branchId, current)),
            canCancelSession = "table_sessions.cancel" in permissions,
            canEditNotes = "orders.update" in permissions,
        )
    }
}
