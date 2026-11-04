package com.restia.mobile.ui.tables

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.TableDto
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

@Composable
private fun tableColor(table: TableDto): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        table.status != "ACTIVE" -> scheme.surfaceVariant
        table.operationalState == "LIBRE" -> scheme.primaryContainer
        table.operationalState == "POR_LIMPIAR" -> scheme.tertiaryContainer
        table.operationalState == "CUENTA_SOLICITADA" -> scheme.errorContainer
        else -> scheme.secondaryContainer
    }
}

@Composable
private fun TableCard(table: TableDto, onClick: (() -> Unit)?) {
    // El estado siempre va en texto: el color solo lo refuerza.
    val colors = CardDefaults.cardColors(containerColor = tableColor(table))
    val content: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.table_number, table.number), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(tableStateLabel(table)), style = MaterialTheme.typography.bodyMedium)
            Text(pluralStringResource(R.plurals.table_capacity, table.capacity, table.capacity), style = MaterialTheme.typography.bodySmall)
            table.zone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (onClick != null) Card(onClick = onClick, colors = colors, content = content) else Card(colors = colors, content = content)
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
    branchName: String,
    role: String,
    canOpenTables: Boolean,
    logoutEnabled: Boolean,
    onLogout: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    state.opening?.let { OpenTableDialog(it, viewModel) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(branchName, style = MaterialTheme.typography.titleMedium)
                        Text(role, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    TextButton(onClick = onLogout, enabled = logoutEnabled) { Text(stringResource(R.string.action_logout)) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.tables_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.openedTableNumber?.let {
                Text(stringResource(R.string.open_done, it), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            TextButton(
                onClick = viewModel::refresh,
                enabled = !state.refreshing,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.action_refresh)) }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.tables.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.tables_empty)) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.tables, key = { it.id }) { table ->
                        // El permiso solo ajusta la interfaz; el backend siempre lo valida.
                        TableCard(table, if (canOpenTables && isOpenable(table)) ({ viewModel.selectTable(table) }) else null)
                    }
                }
            }
        }
    }
}
