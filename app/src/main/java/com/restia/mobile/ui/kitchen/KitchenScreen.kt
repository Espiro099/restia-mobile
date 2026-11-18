package com.restia.mobile.ui.kitchen

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.ui.account.AccountMenu
import com.restia.mobile.ui.tables.waitText
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.FilterTag
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenHeader
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Texto de una falla al empezar o terminar platillos. */
@StringRes
fun kitchenFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.code == "INVENTORY_INSUFFICIENT" -> R.string.kitchen_no_stock
    failure.code == "INVENTORY_BLOCKED_FOOD_SAFETY" -> R.string.kitchen_blocked
    failure.status == 409 -> R.string.kitchen_conflict
    failure.status == 403 -> R.string.error_action_forbidden
    else -> null
}

@Composable
private fun failureText(failure: ApiFailure): String =
    kitchenFailureText(failure)?.let { stringResource(it) } ?: (failure as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected)

/** Hora local y minutos de espera; la espera es lo que cocina mira primero. */
@Composable
private fun ticketTime(iso: String): String? {
    val at = runCatching { OffsetDateTime.parse(iso) }.getOrNull() ?: return null
    val minutes = Duration.between(at, OffsetDateTime.now()).toMinutes().coerceAtLeast(0)
    val time = at.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    return stringResource(R.string.track_time_ago, time, waitText(minutes))
}

@Composable
private fun LineRow(line: KitchenLine, canPrepare: Boolean, working: Boolean, onStart: () -> Unit, onReady: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(line.units.size.toString(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.Top))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(line.productName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            if (line.modifiers.isNotEmpty()) {
                Text(line.modifiers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            line.notes?.let { Text(stringResource(R.string.kitchen_notes, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val pending = line.pending.size
            val preparing = line.preparing.size
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pending > 0) StatusPill(pluralStringResource(R.plurals.unit_pending, pending, pending), MaterialTheme.colorScheme.onSurfaceVariant)
                if (preparing > 0) StatusPill(pluralStringResource(R.plurals.unit_preparing, preparing, preparing), MaterialTheme.colorScheme.onPrimaryContainer)
                if (line.ready > 0) StatusPill(pluralStringResource(R.plurals.kitchen_ready_waiting, line.ready, line.ready), Restia.colors.free)
            }
        }
        if (canPrepare) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (line.pending.isNotEmpty()) {
                    OutlinedButton(onClick = onStart, enabled = !working, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.kitchen_start)) }
                }
                if (line.preparing.isNotEmpty()) {
                    Button(onClick = onReady, enabled = !working, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.kitchen_ready)) }
                }
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: KitchenTicket, canPrepare: Boolean, working: Boolean, viewModel: KitchenViewModel) {
    GroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ticket.tableNumbers.isEmpty()) stringResource(R.string.kitchen_no_table)
                    else pluralStringResource(R.plurals.kitchen_tables, ticket.tableNumbers.size, ticket.tableNumbers.joinToString(", ")),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Restia.colors.heading,
                    modifier = Modifier.weight(1f),
                )
                ticketTime(ticket.createdAt)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Separator()
            ticket.lines.forEach { line ->
                LineRow(line, canPrepare, working, { viewModel.start(line) }, { viewModel.ready(line) })
            }
            // Atajo para una comanda con varias partidas.
            if (canPrepare && ticket.lines.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ticket.pending.isNotEmpty()) TextButton(onClick = { viewModel.startAll(ticket) }, enabled = !working) { Text(stringResource(R.string.kitchen_start_all)) }
                    if (ticket.preparing.isNotEmpty()) TextButton(onClick = { viewModel.readyAll(ticket) }, enabled = !working) { Text(stringResource(R.string.kitchen_ready_all)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KitchenScreen(
    viewModel: KitchenViewModel,
    branchName: String,
    canPrepare: Boolean,
    showTables: Boolean,
    /** Dentro de la barra inferior: Mesas y la cuenta ya están ahí. */
    inTabs: Boolean = false,
    logoutEnabled: Boolean,
    onTables: () -> Unit,
    onLogout: () -> Unit,
    showStaff: Boolean,
    onStaff: () -> Unit,
    onChangePassword: () -> Unit,
    showCash: Boolean = false,
    onCash: () -> Unit = {},
    showPrinting: Boolean = false,
    onPrinting: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.kitchen_title)) {
            if (!inTabs) {
                if (showTables) TextButton(onClick = onTables) { Text(stringResource(R.string.order_back)) }
                AccountMenu(showStaff, logoutEnabled, onStaff, onChangePassword, onLogout, showCash, onCash, showPrinting, onPrinting)
            }
            TextButton(onClick = viewModel::refresh, enabled = !state.refreshing) { Text(stringResource(R.string.action_refresh)) }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                branchName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 12.dp),
            )
            if (state.stations.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterTag(stringResource(R.string.kitchen_all_stations), state.stationId == null) { viewModel.selectStation(null) }
                    state.stations.forEach { station ->
                        FilterTag(station.name, state.stationId == station.id) { viewModel.selectStation(station.id) }
                    }
                }
            }
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.kitchen_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.actionFailure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.units.isEmpty() -> EmptyState(stringResource(R.string.kitchen_empty), stringResource(R.string.kitchen_empty_hint))
                // En tablet (el uso típico en cocina) las comandas se acomodan en columnas; en teléfono queda una.
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp,
                ) {
                    items(state.tickets, key = { it.orderId }) { ticket -> TicketCard(ticket, canPrepare, state.working, viewModel) }
                }
            }
        }
    }
}
