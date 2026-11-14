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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.ui.account.AccountMenu
import com.restia.mobile.core.network.ApiFailure
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
    val minutes = Duration.between(at, OffsetDateTime.now()).toMinutes().coerceAtLeast(0).toInt()
    val time = at.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    return stringResource(R.string.kitchen_time, time, pluralStringResource(R.plurals.kitchen_minutes, minutes, minutes))
}

@Composable
private fun LineRow(line: KitchenLine, canPrepare: Boolean, working: Boolean, onStart: () -> Unit, onReady: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.units.size.toString() + " × " + line.productName, style = MaterialTheme.typography.titleMedium)
            line.modifiers.forEach { Text("+ $it", style = MaterialTheme.typography.bodyMedium) }
            line.notes?.let { Text(stringResource(R.string.kitchen_notes, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            val pending = line.pending.size
            val preparing = line.preparing.size
            if (pending > 0) Text(pluralStringResource(R.plurals.unit_pending, pending, pending), style = MaterialTheme.typography.bodySmall)
            if (preparing > 0) Text(pluralStringResource(R.plurals.unit_preparing, preparing, preparing), style = MaterialTheme.typography.bodySmall)
            if (line.ready > 0) {
                Text(pluralStringResource(R.plurals.kitchen_ready_waiting, line.ready, line.ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (canPrepare) {
            Column(horizontalAlignment = Alignment.End) {
                if (line.pending.isNotEmpty()) OutlinedButton(onClick = onStart, enabled = !working) { Text(stringResource(R.string.kitchen_start)) }
                if (line.preparing.isNotEmpty()) Button(onClick = onReady, enabled = !working) { Text(stringResource(R.string.kitchen_ready)) }
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: KitchenTicket, canPrepare: Boolean, working: Boolean, viewModel: KitchenViewModel) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (ticket.tableNumbers.isEmpty()) stringResource(R.string.kitchen_no_table)
                else pluralStringResource(R.plurals.kitchen_tables, ticket.tableNumbers.size, ticket.tableNumbers.joinToString(", ")),
                style = MaterialTheme.typography.titleLarge,
            )
            ticketTime(ticket.createdAt)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    logoutEnabled: Boolean,
    onTables: () -> Unit,
    onLogout: () -> Unit,
    showStaff: Boolean,
    onStaff: () -> Unit,
    onChangePassword: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.kitchen_title), style = MaterialTheme.typography.titleMedium)
                        Text(branchName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    if (showTables) TextButton(onClick = onTables) { Text(stringResource(R.string.order_back)) }
                    AccountMenu(showStaff, logoutEnabled, onStaff, onChangePassword, onLogout)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.stations.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.stationId == null, onClick = { viewModel.selectStation(null) }, label = { Text(stringResource(R.string.kitchen_all_stations)) })
                    state.stations.forEach { station ->
                        FilterChip(selected = state.stationId == station.id, onClick = { viewModel.selectStation(station.id) }, label = { Text(station.name) })
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
            TextButton(onClick = viewModel::refresh, enabled = !state.refreshing, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.action_refresh))
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.units.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.kitchen_empty)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.tickets, key = { it.orderId }) { ticket -> TicketCard(ticket, canPrepare, state.working, viewModel) }
                }
            }
        }
    }
}
