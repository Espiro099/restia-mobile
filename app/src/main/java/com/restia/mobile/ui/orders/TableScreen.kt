package com.restia.mobile.ui.orders

import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

private const val NOTICE_MILLIS = 5_000L

/** Texto con la cantidad y concordancia: "1 pendiente", "3 pendientes". */
@PluralsRes
fun unitStatusLabel(status: String): Int = when (status) {
    "PENDING" -> R.plurals.unit_pending
    "PREPARING" -> R.plurals.unit_preparing
    "READY" -> R.plurals.unit_ready
    "DELIVERED" -> R.plurals.unit_delivered
    "CANCELLED" -> R.plurals.unit_cancelled
    "VOIDED" -> R.plurals.unit_voided
    else -> R.plurals.unit_unknown
}

/** Texto para una falla de entrega, cancelación o anulación, según el código del contrato. */
@StringRes
fun actionFailureText(failure: ApiFailure, action: String? = "CANCEL"): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.code == "APPROVAL_DENIED" -> R.string.void_denied
    failure.code == "APPROVAL_INVALID" -> R.string.void_approval_invalid
    failure.code == "ORDER_UNIT_INVALID_STATE" && action == "VOID" -> R.string.void_invalid_state
    failure.code == "ORDER_UNIT_PAID" && action == "VOID" -> R.string.void_paid
    failure.code == "ORDER_UNIT_INVALID_STATE" -> R.string.cancel_invalid_state
    failure.code == "ORDER_UNIT_PAID" -> R.string.cancel_paid
    failure.status == 409 -> R.string.table_deliver_conflict
    failure.status == 403 -> R.string.error_action_forbidden
    // Otros 4xx: el mensaje del servidor, si lo trae.
    else -> null
}

private fun roundTime(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()
}

private fun total(money: MoneyDto): String =
    runCatching { moneyText(BigDecimal(money.amount), money.currency) }.getOrDefault(money.amount)

@Composable
private fun loadFailureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun failureText(failure: ApiFailure, action: String? = "CANCEL"): String {
    val seconds = (failure as? ApiFailure.Server)?.takeIf { it.status == 429 }?.retryAfterSeconds
    if (seconds != null) {
        val s = seconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return pluralStringResource(R.plurals.error_too_many, s, s)
    }
    return actionFailureText(failure, action)?.let { stringResource(it) }
        ?: (failure as? ApiFailure.Server)?.message
        ?: stringResource(R.string.error_unexpected)
}

@Composable
private fun ItemRow(
    item: OrderItemDto,
    canDeliver: Boolean,
    canCancel: Boolean,
    canVoid: Boolean,
    busy: Boolean,
    onDeliver: () -> Unit,
    onCancel: () -> Unit,
    onVoid: () -> Unit,
) {
    val ready = readyUnits(listOf(item)).size
    val pending = pendingUnits(item).size
    val voidable = voidableUnits(item).size
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.quantity.toString() + " × " + item.name, style = MaterialTheme.typography.bodyLarge)
            item.modifiers.forEach { Text(it.name, style = MaterialTheme.typography.bodySmall) }
            item.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // El estado siempre va en texto, por unidad agrupada.
            item.units.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                Text(
                    pluralStringResource(unitStatusLabel(status), count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == "READY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (canDeliver && ready > 0) {
                OutlinedButton(onClick = onDeliver, enabled = !busy) { Text(stringResource(R.string.table_deliver, ready)) }
            }
            if (canCancel && pending > 0) {
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel_action)) }
            }
            if (canVoid && voidable > 0) {
                TextButton(onClick = onVoid, enabled = !busy) { Text(stringResource(R.string.void_action)) }
            }
        }
    }
}

@Composable
private fun RoundCard(
    index: Int,
    order: OrderDto,
    canDeliver: Boolean,
    canCancel: Boolean,
    canVoid: Boolean,
    busy: Boolean,
    onDeliver: (OrderItemDto) -> Unit,
    onCancel: (OrderItemDto) -> Unit,
    onVoid: (OrderItemDto) -> Unit,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val time = roundTime(order.createdAt)
            Text(
                if (time != null) stringResource(R.string.table_round_at, index, time) else stringResource(R.string.table_round, index),
                style = MaterialTheme.typography.titleMedium,
            )
            order.items.forEach { item ->
                ItemRow(item, canDeliver, canCancel, canVoid, busy, { onDeliver(item) }, { onCancel(item) }, { onVoid(item) })
            }
            Text(stringResource(R.string.table_round_total, total(order.total)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CancelDialog(state: CancelState, viewModel: TableViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCancel,
        title = { Text(stringResource(R.string.cancel_title, state.item.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.cancel_pending, state.available, state.available))
                if (state.available > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { viewModel.setCancelCount(state.count - 1) }, enabled = !state.submitting && state.count > 1) { Text("−") }
                        Text(state.count.toString(), style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick = { viewModel.setCancelCount(state.count + 1) }, enabled = !state.submitting && state.count < state.available) { Text("+") }
                    }
                }
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = viewModel::setCancelReason,
                    label = { Text(stringResource(R.string.cancel_reason)) },
                    supportingText = { Text(stringResource(R.string.cancel_reason_hint, REASON_MIN)) },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmCancel, enabled = state.canConfirm) {
                Text(stringResource(R.string.cancel_confirm, state.count))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissCancel, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) }
        },
    )
}

@Composable
private fun VoidDialog(state: VoidState, viewModel: TableViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissVoid,
        title = { Text(stringResource(R.string.void_title, state.item.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(pluralStringResource(R.plurals.void_available, state.available, state.available))
                if (state.available > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { viewModel.setVoidCount(state.count - 1) }, enabled = !state.submitting && state.count > 1) { Text("−") }
                        Text(state.count.toString(), style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick = { viewModel.setVoidCount(state.count + 1) }, enabled = !state.submitting && state.count < state.available) { Text("+") }
                    }
                }
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = viewModel::setVoidReason,
                    label = { Text(stringResource(R.string.cancel_reason)) },
                    supportingText = { Text(stringResource(R.string.cancel_reason_hint, REASON_MIN)) },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.showsApprover) {
                    Text(stringResource(R.string.void_approver_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.void_approver_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.approverEmail,
                        onValueChange = viewModel::setApproverEmail,
                        label = { Text(stringResource(R.string.void_approver_email)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.approverPassword,
                        onValueChange = viewModel::setApproverPassword,
                        label = { Text(stringResource(R.string.void_approver_password)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (state.needsApproval) {
                    Text(stringResource(R.string.void_approved), color = MaterialTheme.colorScheme.primary)
                }
                state.failure?.let { Text(failureText(it, "VOID"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmVoid, enabled = state.canConfirm) {
                Text(stringResource(R.string.void_confirm, state.count))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissVoid, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    viewModel: TableViewModel,
    tableNumber: Int,
    canOrder: Boolean,
    canDeliver: Boolean,
    canCancel: Boolean,
    canVoid: Boolean,
    onBack: () -> Unit,
    onAddOrder: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    // Los avisos confirman una acción; dejarlos fijos haría creer que acaba de pasar otra.
    LaunchedEffect(state.sent) {
        if (state.sent) {
            delay(NOTICE_MILLIS)
            viewModel.clearSent()
        }
    }
    LaunchedEffect(state.cancelled) {
        if (state.cancelled != null) {
            delay(NOTICE_MILLIS)
            viewModel.clearCancelled()
        }
    }
    LaunchedEffect(state.voided) {
        if (state.voided != null) {
            delay(NOTICE_MILLIS)
            viewModel.clearVoided()
        }
    }
    state.cancelling?.let { CancelDialog(it, viewModel) }
    state.voiding?.let { VoidDialog(it, viewModel) }
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
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.table_not_loaded) + " " + loadFailureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.actionFailure?.let {
                Text(failureText(it, state.failedAction), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.sent) {
                Text(stringResource(R.string.order_sent, tableNumber), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            state.cancelled?.let {
                Text(pluralStringResource(R.plurals.cancel_done, it, it), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            state.voided?.let {
                Text(pluralStringResource(R.plurals.void_done, it, it), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (canDeliver && state.readyCount > 0) {
                Button(
                    onClick = viewModel::deliverAll,
                    enabled = !state.busy,
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
                        RoundCard(index + 1, order, canDeliver, canCancel, canVoid, state.busy, viewModel::deliverItem, viewModel::startCancel, viewModel::startVoid)
                    }
                }
            }
        }
    }
}
