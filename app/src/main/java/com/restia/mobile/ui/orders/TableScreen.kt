package com.restia.mobile.ui.orders

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.OrderItemDto
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private const val SENT_NOTICE_MILLIS = 5_000L

@StringRes
fun unitStatusLabel(status: String): Int = when (status) {
    "PENDING" -> R.string.unit_pending
    "PREPARING" -> R.string.unit_preparing
    "READY" -> R.string.unit_ready
    "DELIVERED" -> R.string.unit_delivered
    "CANCELLED" -> R.string.unit_cancelled
    "VOIDED" -> R.string.unit_voided
    else -> R.string.unit_unknown
}

private fun roundTime(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()
}

private fun total(money: MoneyDto): String =
    runCatching { moneyText(BigDecimal(money.amount), money.currency) }.getOrDefault(money.amount)

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 409 -> stringResource(R.string.table_deliver_conflict)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun ItemRow(item: OrderItemDto, canDeliver: Boolean, busy: Boolean, onDeliver: () -> Unit) {
    val ready = readyUnits(listOf(item)).size
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.quantity.toString() + " × " + item.name, style = MaterialTheme.typography.bodyLarge)
            item.modifiers.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall) }
            item.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // El estado siempre va en texto, por unidad agrupada.
            item.units.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                Text(
                    count.toString() + " " + stringResource(unitStatusLabel(status)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == "READY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canDeliver && ready > 0) {
            OutlinedButton(onClick = onDeliver, enabled = !busy) { Text(stringResource(R.string.table_deliver, ready)) }
        }
    }
}

@Composable
private fun RoundCard(index: Int, order: OrderDto, canDeliver: Boolean, busy: Boolean, onDeliver: (OrderItemDto) -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val time = roundTime(order.createdAt)
            Text(
                if (time != null) stringResource(R.string.table_round_at, index, time) else stringResource(R.string.table_round, index),
                style = MaterialTheme.typography.titleMedium,
            )
            order.items.forEach { item -> ItemRow(item, canDeliver, busy) { onDeliver(item) } }
            Text(stringResource(R.string.table_round_total, total(order.total)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    viewModel: TableViewModel,
    tableNumber: Int,
    canOrder: Boolean,
    canDeliver: Boolean,
    onBack: () -> Unit,
    onAddOrder: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    // El aviso confirma el envío; dejarlo fijo haría creer que se acaba de enviar otra comanda.
    LaunchedEffect(state.sent) {
        if (state.sent) {
            delay(SENT_NOTICE_MILLIS)
            viewModel.clearSent()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.table_title, tableNumber)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.order_back)) } },
            )
        },
        bottomBar = {
            if (canOrder) {
                Button(onClick = onAddOrder, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.table_add_order))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.table_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.deliverFailure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.sent) {
                Text(stringResource(R.string.order_sent, tableNumber), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (canDeliver && state.readyCount > 0) {
                Button(
                    onClick = viewModel::deliverAll,
                    enabled = !state.delivering,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(pluralStringResource(R.plurals.table_deliver_all, state.readyCount, state.readyCount)) }
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.table_empty)) }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val rounds = state.orders.withIndex().toList()
                    items(rounds, key = { it.value.id }) { (index, order) ->
                        RoundCard(index + 1, order, canDeliver, state.delivering, viewModel::deliverItem)
                    }
                }
            }
        }
    }
}
