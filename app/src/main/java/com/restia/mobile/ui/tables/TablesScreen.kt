package com.restia.mobile.ui.tables

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.ui.orders.TableOrders
import com.restia.mobile.ui.orders.minutesSince
import com.restia.mobile.ui.theme.FilterTag
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.RestiaLogo
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.SectionTitle
import com.restia.mobile.ui.theme.Tone
import com.restia.mobile.ui.theme.toneBorder
import java.time.LocalTime
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

/**
 * Cómo se ve una mesa en el plano (propuesta 02). Una mesa que espera comida y ya tiene platillos listos se
 * muestra "Lista para servir": es lo que el mesero tiene que hacer ahora.
 */
enum class TableLook { FREE, POSSIBLE, NO_ORDER, WAITING, READY, EATING, CHECK, CLEANING, OFF }

fun tableLook(table: TableDto, orders: TableOrders?): TableLook = when {
    table.status != "ACTIVE" -> TableLook.OFF
    else -> when (table.operationalState) {
        "LIBRE" -> TableLook.FREE
        "POSIBLE_OCUPACION" -> TableLook.POSSIBLE
        "OCUPADA_SIN_COMANDA" -> TableLook.NO_ORDER
        "ESPERANDO_COMIDA", "CONSUMIENDO" -> when {
            (orders?.ready ?: 0) > 0 -> TableLook.READY
            table.operationalState == "ESPERANDO_COMIDA" -> TableLook.WAITING
            else -> TableLook.EATING
        }
        "CUENTA_SOLICITADA" -> TableLook.CHECK
        "POR_LIMPIAR" -> TableLook.CLEANING
        else -> TableLook.OFF
    }
}

@Composable
private fun lookTone(look: TableLook): Tone {
    val t = Restia.colors.tables
    return when (look) {
        TableLook.FREE -> t.free
        TableLook.POSSIBLE -> t.possible
        TableLook.NO_ORDER -> t.noOrder
        TableLook.WAITING -> t.waiting
        TableLook.READY, TableLook.EATING -> t.eating
        TableLook.CHECK -> t.check
        TableLook.CLEANING -> t.cleaning
        TableLook.OFF -> t.off
    }
}

@DrawableRes
private fun lookIcon(look: TableLook): Int = when (look) {
    TableLook.FREE, TableLook.READY -> R.drawable.ic_state_check
    TableLook.POSSIBLE, TableLook.OFF -> R.drawable.ic_info
    TableLook.NO_ORDER -> R.drawable.ic_guests
    TableLook.WAITING -> R.drawable.ic_clock
    TableLook.EATING -> R.drawable.ic_cup
    TableLook.CHECK -> R.drawable.ic_card
    TableLook.CLEANING -> R.drawable.ic_broom
}

/** Segunda línea de la tarjeta: lo más útil para cada estado (lugares, minutos, platillos listos...). */
@Composable
private fun lookDetail(look: TableLook, table: TableDto, orders: TableOrders?): String {
    val seats = pluralStringResource(R.plurals.table_seats, table.capacity, table.capacity)
    return when (look) {
        TableLook.POSSIBLE -> stringResource(R.string.table_to_confirm)
        TableLook.CHECK -> stringResource(R.string.table_to_charge)
        TableLook.READY -> orders!!.ready.let { pluralStringResource(R.plurals.table_dishes, it, it) }
        TableLook.WAITING -> orders?.let { minutesSince(it.oldest) }?.let { waitText(it) } ?: seats
        TableLook.CLEANING, TableLook.OFF -> table.zone ?: seats
        else -> seats
    }
}

/** Espera corta en minutos; si se alarga, en horas o días ("24 min", "3 h", "2 d"). */
@Composable
fun waitText(minutes: Long): String = when {
    minutes < 60 -> stringResource(R.string.table_minutes, minutes.toInt())
    minutes < 24 * 60 -> stringResource(R.string.table_hours, (minutes / 60).toInt())
    else -> stringResource(R.string.table_days, (minutes / (24 * 60)).toInt())
}

@Composable
private fun TableCard(table: TableDto, orders: TableOrders?, onClick: (() -> Unit)?) {
    val look = tableLook(table, orders)
    val tone = lookTone(look)
    val label = if (look == TableLook.READY) stringResource(R.string.table_ready_to_serve) else stringResource(tableStateLabel(table))
    val detail = lookDetail(look, table, orders)
    val number = stringResource(R.string.table_number, table.number)
    val shape = MaterialTheme.shapes.medium
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier = Modifier
            .heightIn(min = 124.dp)
            .clip(shape)
            .background(tone.container)
            .toneBorder(tone, shape, 12.dp)
            .then(clickable)
            .padding(12.dp)
            // El estado siempre va en texto: el color solo lo refuerza.
            .clearAndSetSemantics { contentDescription = "$number, $label, $detail" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "%02d".format(table.number),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = tone.content,
                modifier = Modifier.weight(1f),
            )
            Icon(painterResource(lookIcon(look)), contentDescription = null, tint = tone.content, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = tone.content, minLines = 2, maxLines = 2)
        Text(detail, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = tone.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Leyenda de la propuesta: libre, en atención y cuenta. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            Restia.colors.free to R.string.legend_free,
            Restia.colors.busy to R.string.legend_attention,
            Restia.colors.attention to R.string.legend_check,
        ).forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Buenos días", "Buenas tardes" o "Buenas noches", según la hora del teléfono. */
@StringRes
fun greetingFor(time: LocalTime): Int = when (time.hour) {
    in 5..11 -> R.string.greeting_morning
    in 12..18 -> R.string.greeting_afternoon
    else -> R.string.greeting_evening
}

/** Aviso accionable: la mesa con platillos listos que más espera ("Mesa 8 · orden lista"). */
@Composable
private fun ReadyNotice(orders: TableOrders, onClick: (() -> Unit)?) {
    val tone = Restia.colors.notice
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tone.container)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_kitchen), contentDescription = null, tint = Restia.colors.busy, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ready_notice_title, orders.tableNumber), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = tone.content)
            Text(
                pluralStringResource(R.plurals.ready_notice_body, orders.ready, orders.ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Restia.colors.busy, modifier = Modifier.size(16.dp))
        }
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

/** Mesas (propuesta 02): saludo, zonas, el aviso de lo que está listo y el plano por estado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    viewModel: TablesViewModel,
    userName: String,
    branchName: String,
    canOpenTables: Boolean,
    canOrder: Boolean,
    canViewOrders: Boolean,
    canClean: Boolean,
    /** Lo que cada mesa tiene en cocina, para el aviso de "orden lista" y las tarjetas. */
    activeOrders: List<TableOrders> = emptyList(),
    onViewTable: (sessionId: String, tableNumber: Int) -> Unit,
    onOrder: (sessionId: String, tableNumber: Int) -> Unit,
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
    // null = todas las zonas.
    var zone by rememberSaveable { mutableStateOf<String?>(null) }
    val zones = state.tables.mapNotNull { it.zone }.distinct()
    val selectedZone = zone?.takeIf { it in zones }
    val shown = state.tables.filter { selectedZone == null || it.zone == selectedZone }
    val byTable = activeOrders.associateBy { it.tableNumber }
    fun openView(number: Int): (() -> Unit)? {
        val table = state.tables.firstOrNull { it.number == number } ?: return null
        val session = table.currentSessionId ?: return null
        return if (canViewOrders && hasSession(table)) ({ onViewTable(session, number) }) else null
    }
    val first = userName.trim().substringBefore(' ').ifEmpty { userName }
    val ready = activeOrders.firstOrNull { it.ready > 0 && it.tableNumber in state.tables.map { t -> t.number } }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RestiaLogo(height = 33.dp)
        }
        PullToRefreshBox(isRefreshing = state.refreshing && state.loaded, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "encabezado", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        if (first.isNotBlank()) {
                            Text(
                                stringResource(greetingFor(LocalTime.now()), first),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(R.string.tab_tables), Modifier.weight(1f))
                            Text(branchName, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Restia.colors.heading)
                        }
                        if (zones.isNotEmpty()) {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterTag(stringResource(R.string.zone_all, state.tables.size), selectedZone == null) { zone = null }
                                zones.forEach { name ->
                                    FilterTag(stringResource(R.string.zone_tag, name, state.tables.count { it.zone == name }), selectedZone == name) { zone = name }
                                }
                            }
                        }
                        ready?.let { ReadyNotice(it, openView(it.tableNumber)) }
                        state.failure?.let {
                            Text(
                                stringResource(if (state.loaded) R.string.tables_stale else R.string.tables_not_loaded) + " " + failureText(it),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        state.cleaned?.let { Text(stringResource(R.string.cleaning_done, it), style = MaterialTheme.typography.bodySmall) }
                        state.pendingDrafts.forEach { draft ->
                            TextButton(onClick = { onOrder(draft.sessionId, draft.tableNumber) }) {
                                Text(stringResource(R.string.tables_pending_draft, draft.tableNumber))
                            }
                        }
                    }
                }
                when {
                    !state.loaded && state.failure == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                    }
                    state.loaded && state.tables.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.tables_empty), modifier = Modifier.padding(vertical = 24.dp))
                    }
                    else -> {
                        items(shown, key = { it.id }) { table ->
                            // El permiso solo ajusta la interfaz; el backend siempre lo valida.
                            val session = table.currentSessionId
                            TableCard(
                                table,
                                byTable[table.number],
                                when {
                                    canOpenTables && isOpenable(table) -> ({ viewModel.selectTable(table) })
                                    canViewOrders && session != null && hasSession(table) -> ({ onViewTable(session, table.number) })
                                    canClean && needsCleaning(table) -> ({ viewModel.selectCleaning(table) })
                                    else -> null
                                },
                            )
                        }
                        item(key = "leyenda", span = { GridItemSpan(maxLineSpan) }) { Legend(Modifier.padding(top = 4.dp)) }
                    }
                }
            }
        }
    }
}
