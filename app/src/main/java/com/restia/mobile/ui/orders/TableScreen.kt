package com.restia.mobile.ui.orders

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.ui.tables.MAX_GUESTS
import com.restia.mobile.ui.tables.waitText
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.OptionRow
import com.restia.mobile.ui.theme.PrimaryButton
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.RestiaLogo
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.SecondaryButton
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import com.restia.mobile.ui.theme.readableWidth
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

/** Pasos del seguimiento de una ronda (propuesta 4). */
val ROUND_STEPS = listOf(R.string.track_received, R.string.track_preparing, R.string.track_ready, R.string.track_served)

private fun unitStage(status: String): Int? = when (status) {
    "PENDING" -> 0
    "PREPARING" -> 1
    "READY" -> 2
    "DELIVERED" -> 3
    else -> null
}

/**
 * Paso en el que va la ronda: el de su unidad más atrasada, porque la ronda no está lista hasta que lo está todo.
 * Las unidades canceladas o anuladas no cuentan; si no queda ninguna, la ronda no tiene seguimiento (null).
 */
fun roundStage(order: OrderDto): Int? =
    order.items.flatMap { it.units }.mapNotNull { unitStage(it.status) }.minOrNull()

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

/** Texto de una falla al cerrar la mesa (T-4). */
@StringRes
fun closeFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.code == "SESSION_HAS_PENDING_UNITS" -> R.string.close_pending_units
    failure.code == "SESSION_HAS_OPEN_CHECKS" -> R.string.close_open_checks
    failure.code == "VERSION_CONFLICT" -> R.string.close_conflict
    failure.status == 403 -> R.string.error_action_forbidden
    else -> null
}

@Composable
private fun CloseDialog(state: CloseState, tableNumber: Int, viewModel: TableViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissClose,
        title = { Text(stringResource(R.string.close_title, tableNumber)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.close_body))
                state.failure?.let {
                    Text(
                        closeFailureText(it)?.let { id -> stringResource(id) } ?: (it as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmClose, enabled = !state.submitting) { Text(stringResource(R.string.close_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissClose, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) }
        },
    )
}

/** Texto de una falla al cambiar los comensales. */
@StringRes
fun guestsFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.code == "VERSION_CONFLICT" -> R.string.guests_conflict
    failure.code == "SESSION_NOT_ACTIVE" -> R.string.guests_inactive
    failure.status == 403 -> R.string.error_action_forbidden
    else -> null
}

@Composable
private fun GuestsDialog(state: GuestsState, tableNumber: Int, viewModel: TableViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissGuests,
        title = { Text(stringResource(R.string.guests_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.guests_body, tableNumber))
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (state.version != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    ) {
                        val less = stringResource(R.string.guests_less)
                        val more = stringResource(R.string.guests_more)
                        OutlinedButton(
                            onClick = { viewModel.setGuests(state.count - 1) },
                            enabled = !state.submitting && state.count > 1,
                            modifier = Modifier.semantics { contentDescription = less },
                        ) {
                            Text("−")
                        }
                        Text(
                            state.count.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = Restia.colors.heading,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        OutlinedButton(
                            onClick = { viewModel.setGuests(state.count + 1) },
                            enabled = !state.submitting && state.count < MAX_GUESTS,
                            modifier = Modifier.semantics { contentDescription = more },
                        ) {
                            Text("+")
                        }
                    }
                }
                state.failure?.let {
                    Text(
                        guestsFailureText(it)?.let { id -> stringResource(id) } ?: (it as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (!state.loading && state.version == null) {
                TextButton(onClick = viewModel::retryGuests) { Text(stringResource(R.string.action_retry)) }
            } else {
                TextButton(onClick = viewModel::confirmGuests, enabled = state.canConfirm) { Text(stringResource(R.string.guests_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissGuests, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) }
        },
    )
}

/** Color de cada estado de unidad: el mismo código que el plano de mesas. */
@Composable
private fun unitTone(status: String): Color = when (status) {
    "PENDING" -> MaterialTheme.colorScheme.outline
    "PREPARING" -> Restia.colors.busy
    "READY" -> Restia.colors.free
    "DELIVERED" -> Restia.colors.heading
    else -> MaterialTheme.colorScheme.error
}

/** Verde de un paso ya cumplido del seguimiento (propuesta 04). */
private val StepDone = Color(0xFF2F9E5B)

/**
 * Estado de la cocina (propuesta 04): cuatro círculos unidos por una línea. Cumplido en verde, el actual en
 * naranja (ambos con palomita) y lo pendiente en crema con un reloj. Debajo, el nombre del paso y, para lo
 * pendiente, "Pendiente".
 */
@Composable
private fun RoundStepper(stage: Int, startedAt: String?) {
    val line = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // La línea va detrás de los círculos, del centro del primero al del último (un octavo del ancho a cada lado).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = maxWidth / (ROUND_STEPS.size * 2))
                .padding(top = 22.dp)
                .height(1.dp)
                .background(line),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            ROUND_STEPS.forEachIndexed { i, label ->
                val done = i < stage || (i == stage && i == ROUND_STEPS.lastIndex)
                val current = i == stage && !done
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val fill = when {
                        done -> StepDone
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.background
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .border(1.dp, if (done || current) fill else Color(0xFFD5B79C), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (done || current) R.drawable.ic_state_check else R.drawable.ic_clock),
                            contentDescription = null,
                            tint = if (done || current) Color(0xFFFFFCF9) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Normal),
                        textAlign = TextAlign.Center,
                    )
                    val detail = when {
                        i == 0 -> startedAt
                        i > stage -> stringResource(R.string.track_pending)
                        else -> null
                    }
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.quantity.toString(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            val notes = item.modifiers.map { it.name } + listOfNotNull(item.notes)
            if (notes.isNotEmpty()) {
                Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // El estado siempre va en texto, por unidad agrupada.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.units.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                    StatusPill(pluralStringResource(unitStatusLabel(status), count, count), unitTone(status))
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (canDeliver && ready > 0) {
                TextButton(onClick = onDeliver, enabled = !busy) { Text(stringResource(R.string.table_deliver, ready)) }
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

/** Una ronda (comanda enviada): su número y hora, sus platillos, el estado de la cocina y su total. */
@Composable
private fun RoundSection(
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
    val stage = roundStage(order)
    val time = roundTime(order.createdAt)
    val minutes = order.createdAt?.let { minutesSince(it) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row {
            Text(
                stringResource(R.string.table_round, index),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (time != null) {
                Text(
                    if (minutes != null) stringResource(R.string.track_time_ago, time, waitText(minutes)) else time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Separator()
        order.items.forEach { item ->
            ItemRow(item, canDeliver, canCancel, canVoid, busy, { onDeliver(item) }, { onCancel(item) }, { onVoid(item) })
        }
        Separator()
        if (stage != null) {
            Row {
                Text(stringResource(R.string.track_kitchen), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                if (minutes != null && stage < ROUND_STEPS.lastIndex) {
                    Text(stringResource(R.string.track_elapsed, waitText(minutes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RoundStepper(stage, time)
        }
        Row {
            Text(stringResource(R.string.table_round_subtotal), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(total(order.total), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Una acción de la hoja "Acciones de Mesa N". */
private class TableAction(@DrawableRes val icon: Int, val label: Int, val onClick: () -> Unit)

/** Acciones de mesa (propuesta 08): lo que se puede hacer con la mesa, y cerrarla al final. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableActionsSheet(tableNumber: Int, actions: List<TableAction>, onClose: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.table_actions_title, tableNumber), style = MaterialTheme.typography.headlineSmall, color = Restia.colors.heading)
            Text(
                stringResource(R.string.table_actions_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            actions.forEach { action ->
                OptionRow(action.icon, stringResource(action.label), tint = MaterialTheme.colorScheme.onSurface, onClick = {
                    onDismiss()
                    action.onClick()
                })
            }
            if (onClose != null) {
                Separator(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        onDismiss()
                        onClose()
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.close_action), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }
                Text(stringResource(R.string.table_close_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

/** Seguimiento de la orden (propuesta 04): las rondas de la mesa, su estado en cocina y sus acciones. */
@Composable
fun TableScreen(
    viewModel: TableViewModel,
    tableNumber: Int,
    canOrder: Boolean,
    canDeliver: Boolean,
    canCancel: Boolean,
    canVoid: Boolean,
    canClose: Boolean,
    canViewCheck: Boolean,
    onBack: () -> Unit,
    canChangeGuests: Boolean = false,
    onAddOrder: () -> Unit,
    onCheck: () -> Unit,
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
    LaunchedEffect(state.closed) {
        if (state.closed) {
            viewModel.consumeClosed()
            onBack()
        }
    }
    LaunchedEffect(state.guestsChanged) {
        if (state.guestsChanged != null) {
            delay(NOTICE_MILLIS)
            viewModel.clearGuestsChanged()
        }
    }
    state.closing?.let { CloseDialog(it, tableNumber, viewModel) }
    state.guests?.let { GuestsDialog(it, tableNumber, viewModel) }
    state.cancelling?.let { CancelDialog(it, viewModel) }
    state.voiding?.let { VoidDialog(it, viewModel) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    val actions = buildList {
        if (canOrder) add(TableAction(R.drawable.ic_add, R.string.table_add_order, onAddOrder))
        if (canViewCheck) add(TableAction(R.drawable.ic_receipt, R.string.table_view_check, onCheck))
        if (canChangeGuests) add(TableAction(R.drawable.ic_guests, R.string.table_change_guests, viewModel::startGuests))
    }
    val closeAction = if (canClose && !state.busy) viewModel::startClose else null
    if (showActions) TableActionsSheet(tableNumber, actions, closeAction) { showActions = false }
    // El estado de la mesa es el de su ronda más atrasada que siga en cocina.
    val stage = state.orders.mapNotNull { roundStage(it) }.filter { it < ROUND_STEPS.lastIndex }.minOrNull()
        ?: state.orders.mapNotNull { roundStage(it) }.maxOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(
            title = null,
            onBack = onBack,
            center = { RestiaLogo(height = 32.dp) },
            actions = {
                if (actions.isNotEmpty() || closeAction != null) {
                    IconButton(onClick = { showActions = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.table_actions_title, tableNumber), modifier = Modifier.size(22.dp))
                    }
                }
            },
        )
        Column(modifier = Modifier.weight(1f).readableWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = if (state.guestCount == null) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.table_title, tableNumber),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 23.sp, lineHeight = 32.sp),
                    color = Restia.colors.heading,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                stage?.let { StatusPill(stringResource(ROUND_STEPS[it]), stageTone(it)) }
            }
            state.guestCount?.let {
                Text(
                    pluralStringResource(R.plurals.table_guests, it, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 12.dp),
                )
            }
            val notices = buildList {
                state.failure?.let { add(stringResource(if (state.loaded) R.string.tables_stale else R.string.table_not_loaded) + " " + loadFailureText(it) to true) }
                state.actionFailure?.let { add(failureText(it, state.failedAction) to true) }
                if (state.sent) add(stringResource(R.string.order_sent, tableNumber) to false)
                state.cancelled?.let { add(pluralStringResource(R.plurals.cancel_done, it, it) to false) }
                state.voided?.let { add(pluralStringResource(R.plurals.void_done, it, it) to false) }
                state.guestsChanged?.let { add(pluralStringResource(R.plurals.guests_changed, it, it) to false) }
            }
            notices.forEach { (text, error) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else Restia.colors.free,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
            if (canDeliver && state.readyCount > 0) {
                PrimaryButton(
                    pluralStringResource(R.plurals.table_deliver_all, state.readyCount, state.readyCount),
                    onClick = viewModel::deliverAll,
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
                )
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.orders.isEmpty() -> EmptyState(
                    stringResource(R.string.table_empty),
                    if (canOrder) stringResource(R.string.table_empty_hint) else null,
                    action = if (canOrder) stringResource(R.string.table_add_order) else null,
                    onAction = onAddOrder,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    val rounds = state.orders.withIndex().toList()
                    items(rounds, key = { it.value.id }) { (index, order) ->
                        RoundSection(index + 1, order, canDeliver, canCancel, canVoid, state.busy, viewModel::deliverItem, viewModel::startCancel, viewModel::startVoid)
                    }
                }
            }
        }
        if (state.orders.isNotEmpty() && (canViewCheck || canOrder)) {
            Column(
                modifier = Modifier.readableWidth().navigationBarsPadding().padding(start = ScreenPadding, end = ScreenPadding, bottom = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (canOrder) PrimaryButton(stringResource(R.string.table_add_order), onAddOrder)
                if (canViewCheck) SecondaryButton(stringResource(R.string.table_view_check_of, tableNumber), onCheck)
                Text(
                    stringResource(R.string.track_live),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Color de la etiqueta del paso: oro en preparación, verde al estar lista o servida, gris al recibirla. */
@Composable
private fun stageTone(stage: Int): Color = when (stage) {
    0 -> MaterialTheme.colorScheme.onSurfaceVariant
    1 -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> Restia.colors.free
}
