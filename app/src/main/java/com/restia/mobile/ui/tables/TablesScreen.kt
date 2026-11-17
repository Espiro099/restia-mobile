package com.restia.mobile.ui.tables

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.ui.account.AccountMenu
import com.restia.mobile.ui.theme.Avatar
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.RestiaLogo
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Nombre del rol para el empleado; un rol nuevo del backend se muestra tal cual. */
@StringRes
fun roleLabel(role: String): Int? = when (role) {
    "ADMIN" -> R.string.role_admin
    "MANAGER" -> R.string.role_manager
    "WAITER" -> R.string.role_waiter
    "KITCHEN" -> R.string.role_kitchen
    else -> null
}

@StringRes
fun tableStateLabel(table: TableDto): Int = when {
    table.status == "INACTIVE" -> R.string.table_inactive
    table.status == "MAINTENANCE" -> R.string.table_maintenance
    else -> when (table.operationalState) {
        "LIBRE" -> R.string.table_free
        "POSIBLE_OCUPACION" -> R.string.table_possibly_occupied
        "OCUPADA_SIN_COMANDA" -> R.string.table_no_order
        "ESPERANDO_COMIDA" -> R.string.table_waiting_food
        "CONSUMIENDO" -> R.string.table_eating
        "CUENTA_SOLICITADA" -> R.string.table_check_requested
        "POR_LIMPIAR" -> R.string.table_to_clean
        else -> R.string.table_unknown
    }
}

/** Los cinco grupos de la leyenda: libre, ocupada, cuenta, por limpiar y fuera de servicio. */
enum class TableTone { FREE, BUSY, CHECK, CLEANING, OFF }

fun tableTone(table: TableDto): TableTone = when {
    table.status != "ACTIVE" -> TableTone.OFF
    table.operationalState == "LIBRE" -> TableTone.FREE
    table.operationalState == "CUENTA_SOLICITADA" -> TableTone.CHECK
    table.operationalState == "POR_LIMPIAR" -> TableTone.CLEANING
    else -> TableTone.BUSY
}

@Composable
private fun toneColor(tone: TableTone): Color {
    val c = Restia.colors
    return when (tone) {
        TableTone.FREE -> c.free
        TableTone.BUSY -> c.busy
        TableTone.CHECK -> c.attention
        TableTone.CLEANING -> c.idle
        TableTone.OFF -> c.idle.copy(alpha = 0.45f)
    }
}

@StringRes
private fun toneLabel(tone: TableTone): Int = when (tone) {
    TableTone.FREE -> R.string.table_free
    TableTone.BUSY -> R.string.legend_busy
    TableTone.CHECK -> R.string.table_check_requested
    TableTone.CLEANING -> R.string.table_to_clean
    TableTone.OFF -> R.string.legend_off
}

/** La mesa vista desde arriba: cuatro sillas y la mesa al centro con su número, en el color de su estado. */
@Composable
private fun TableGlyph(number: Int, color: Color, modifier: Modifier = Modifier) {
    // Texto oscuro sobre mostaza y gris (el blanco no alcanza contraste); blanco sobre verde y rojo.
    val numberColor = if (color.luminance() > 0.3f) Color(0xFF2B1D16) else Color.White
    Box(modifier = modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val chair = CornerRadius(w * 0.06f)
            val long = w * 0.30f
            val short = w * 0.13f
            // Sillas: arriba, abajo, izquierda y derecha.
            drawRoundRect(color, Offset((w - long) / 2f, 0f), Size(long, short), chair)
            drawRoundRect(color, Offset((w - long) / 2f, w - short), Size(long, short), chair)
            drawRoundRect(color, Offset(0f, (w - long) / 2f), Size(short, long), chair)
            drawRoundRect(color, Offset(w - short, (w - long) / 2f), Size(short, long), chair)
            drawCircle(color, radius = w * 0.31f)
        }
        Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = numberColor)
    }
}

@Composable
private fun TableCard(table: TableDto, onClick: (() -> Unit)?) {
    // El estado siempre va en texto: el color solo lo refuerza.
    val tone = tableTone(table)
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(clickable)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TableGlyph(table.number, toneColor(tone))
        Text(
            stringResource(R.string.table_number, table.number),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            stringResource(tableStateLabel(table)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            // Dos líneas siempre: así todas las tarjetas miden lo mismo.
            minLines = 2,
            maxLines = 2,
        )
        Text(
            pluralStringResource(R.plurals.table_capacity, table.capacity, table.capacity) + (table.zone?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Leyenda de colores: solo los estados que hay en el salón ahora mismo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(tables: List<TableDto>) {
    val tones = tables.map(::tableTone).toSet()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TableTone.entries.filter { it in tones }.forEach { tone ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(toneColor(tone)))
                Text(stringResource(toneLabel(tone)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Saludo con el empleado, su rol y la sucursal, como en la propuesta. */
@Composable
private fun Greeting(userName: String, role: String, branchName: String, refreshing: Boolean, onRefresh: () -> Unit) {
    val first = userName.trim().substringBefore(' ').ifEmpty { userName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(userName)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.greeting, first), style = MaterialTheme.typography.titleMedium)
            Text(
                (roleLabel(role)?.let { stringResource(it) } ?: role) + " · " + branchName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefresh, enabled = !refreshing) { Text(stringResource(R.string.action_refresh)) }
    }
}

private fun localTime(iso: String): String? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

@Composable
private fun OpenTableDialog(state: OpenTableState, viewModel: TablesViewModel) {
    val reservation = state.reservationStartAt
    AlertDialog(
        onDismissRequest = viewModel::dismissOpen,
        title = { Text(stringResource(R.string.open_title, state.table.number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.open_guests))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.setGuests(state.guests - 1) }, enabled = !state.submitting && state.guests > 1) { Text("−") }
                    Text(state.guests.toString(), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { viewModel.setGuests(state.guests + 1) }, enabled = !state.submitting && state.guests < MAX_GUESTS) { Text("+") }
                }
                if (reservation != null) {
                    val time = localTime(reservation)
                    Text(
                        if (time != null) stringResource(R.string.open_reserved_at, time) else stringResource(R.string.open_reserved),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmOpen, enabled = !state.submitting) {
                Text(stringResource(if (reservation != null) R.string.open_anyway else R.string.action_open))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissOpen, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CleaningDialog(state: CleaningState, viewModel: TablesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCleaning,
        title = { Text(stringResource(R.string.cleaning_title, state.table.number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cleaning_body))
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmCleaning, enabled = !state.submitting) { Text(stringResource(R.string.cleaning_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissCleaning, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status == 403 -> failure.message ?: stringResource(R.string.error_forbidden)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    viewModel: TablesViewModel,
    userName: String = "",
    /** Dentro de la barra inferior: Reservas, Cocina y la cuenta ya están ahí. */
    inTabs: Boolean = false,
    branchName: String,
    role: String,
    canOpenTables: Boolean,
    canOrder: Boolean,
    canViewOrders: Boolean,
    canClean: Boolean,
    showKitchen: Boolean,
    showReservations: Boolean,
    logoutEnabled: Boolean,
    onLogout: () -> Unit,
    showStaff: Boolean,
    onStaff: () -> Unit,
    onChangePassword: () -> Unit,
    showCash: Boolean = false,
    onCash: () -> Unit = {},
    showPrinting: Boolean = false,
    onPrinting: () -> Unit = {},
    onViewTable: (sessionId: String, tableNumber: Int) -> Unit,
    onOrder: (sessionId: String, tableNumber: Int) -> Unit,
    onKitchen: () -> Unit,
    onReservations: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    state.opening?.let { OpenTableDialog(it, viewModel) }
    state.cleaning?.let { CleaningDialog(it, viewModel) }
    LaunchedEffect(state.cleaned) {
        if (state.cleaned != null) {
            delay(5_000)
            viewModel.clearCleaned()
        }
    }
    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.consumeOpened()
            if (canOrder) onOrder(it.sessionId, it.tableNumber) else if (canViewOrders) onViewTable(it.sessionId, it.tableNumber)
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { RestiaLogo(height = 26.dp) },
                actions = {
                    if (!inTabs) {
                        if (showReservations) TextButton(onClick = onReservations) { Text(stringResource(R.string.reservations_title)) }
                        if (showKitchen) TextButton(onClick = onKitchen) { Text(stringResource(R.string.kitchen_title)) }
                        AccountMenu(showStaff, logoutEnabled, onStaff, onChangePassword, onLogout, showCash, onCash, showPrinting, onPrinting)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Greeting(userName.ifBlank { branchName }, role, branchName, state.refreshing, viewModel::refresh)
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.tables_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.cleaned?.let {
                Text(stringResource(R.string.cleaning_done, it), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            state.pendingDrafts.forEach { draft ->
                TextButton(
                    onClick = { onOrder(draft.sessionId, draft.tableNumber) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.tables_pending_draft, draft.tableNumber)) }
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.tables.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.tables_empty)) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.tables, key = { it.id }) { table ->
                        // El permiso solo ajusta la interfaz; el backend siempre lo valida.
                        val session = table.currentSessionId
                        TableCard(
                            table,
                            when {
                                canOpenTables && isOpenable(table) -> ({ viewModel.selectTable(table) })
                                canViewOrders && session != null && hasSession(table) -> ({ onViewTable(session, table.number) })
                                canClean && needsCleaning(table) -> ({ viewModel.selectCleaning(table) })
                                else -> null
                            },
                        )
                    }
                }
            }
            if (state.loaded && state.tables.isNotEmpty()) Legend(state.tables)
        }
    }
}
