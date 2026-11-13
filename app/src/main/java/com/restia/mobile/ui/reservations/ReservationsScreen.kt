package com.restia.mobile.ui.reservations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ReservationDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lo que el rol puede hacer; el backend siempre lo valida (D-12). */
data class ReservationPermissions(
    val create: Boolean,
    val confirm: Boolean,
    val arrive: Boolean,
    val seat: Boolean,
    val cancel: Boolean,
    val noShow: Boolean,
    val overridePriority: Boolean,
)

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-MX"))

/** Red y servidor tienen texto propio; los conflictos de reservas traen un mensaje claro del backend. */
@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 403 -> stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server && failure.status == 422 -> fieldMessage(failure) ?: failure.message ?: stringResource(R.string.error_unexpected)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Primer mensaje de `error.details.fields`, por ejemplo "Fuera del horario de reservas de la sucursal." */
fun fieldMessage(failure: ApiFailure.Server): String? = runCatching {
    failure.details?.get("fields")?.jsonObject?.values?.firstOrNull()?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun hours(r: ReservationDto, zone: ZoneId): String {
    val start = localTime(r.startAt, zone)?.format(HOUR) ?: "?"
    val end = localTime(r.endAt, zone)?.format(HOUR) ?: "?"
    return "$start–$end"
}

@Composable
private fun tablesText(numbers: List<Int>): String =
    if (numbers.isEmpty()) stringResource(R.string.reservation_no_tables)
    else pluralStringResource(R.plurals.kitchen_tables, numbers.size, numbers.joinToString(", "))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReservationCard(
    r: ReservationDto,
    state: ReservationsUiState,
    can: ReservationPermissions,
    zone: ZoneId,
    position: Int?,
    viewModel: ReservationsViewModel,
) {
    val waiting = r.status == "ARRIVED_WAITING"
    val colors = if (waiting) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()
    Card(colors = colors) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (position?.let { "$it. " } ?: "") + hours(r, zone) + " · " + pluralStringResource(R.plurals.table_capacity, r.partySize, r.partySize),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(reservationStatusLabel(r.status)), style = MaterialTheme.typography.labelLarge)
            }
            Text(r.customer.name + (r.customer.phone?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
            Text(tablesText(state.tableNumbers(r.plannedTableIds)) + " · " + stringResource(R.string.reservation_code, r.confirmationCode), style = MaterialTheme.typography.bodySmall)
            r.arrivalRegisteredAt?.let { localTime(it, zone) }?.let { Text(stringResource(R.string.reservation_arrived_at, it.format(HOUR)), style = MaterialTheme.typography.bodySmall) }
            if (r.planConflict) Text(stringResource(R.string.reservation_plan_conflict), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            r.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = state.busy
                if (can.seat && canSeat(r)) Button(onClick = { viewModel.startSeat(r) }, enabled = !busy) { Text(stringResource(R.string.reservation_seat)) }
                if (can.arrive && canArrive(r)) OutlinedButton(onClick = { viewModel.arrive(r) }, enabled = !busy) { Text(stringResource(R.string.reservation_arrive)) }
                if (can.confirm && canConfirm(r)) OutlinedButton(onClick = { viewModel.confirm(r) }, enabled = !busy) { Text(stringResource(R.string.reservation_confirm)) }
                if (can.noShow && canMarkNoShow(r, Instant.now())) TextButton(onClick = { viewModel.markNoShow(r) }, enabled = !busy) { Text(stringResource(R.string.reservation_mark_no_show)) }
                if (can.cancel && canCancel(r)) TextButton(onClick = { viewModel.startCancel(r) }, enabled = !busy) { Text(stringResource(R.string.reservation_cancel)) }
            }
        }
    }
}

/** Con `onPick`, tocar el valor abre el selector nativo; los botones quedan para ajustes finos. */
@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean, onPick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, enabled = enabled) { Text("−") }
        if (onPick != null) {
            TextButton(onClick = onPick, enabled = enabled) { Text(value, style = MaterialTheme.typography.titleMedium) }
        } else {
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = onPlus, enabled = enabled) { Text("+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDialog(c: CreateState, zone: ZoneId, viewModel: ReservationsViewModel) {
    val enabled = !c.submitting
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = viewModel::dismissCreate,
        title = { Text(stringResource(R.string.reservation_new)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(stringResource(R.string.reservation_day), c.date.format(DAY), { viewModel.shiftDate(-1) }, { viewModel.shiftDate(1) }, enabled) {
                    DatePickerDialog(context, { _, y, m, d -> viewModel.setDate(LocalDate.of(y, m + 1, d)) }, c.date.year, c.date.monthValue - 1, c.date.dayOfMonth).apply {
                        datePicker.minDate = System.currentTimeMillis() - 1000
                    }.show()
                }
                Stepper(stringResource(R.string.reservation_start), c.start.format(HOUR), { viewModel.shiftStart(-15) }, { viewModel.shiftStart(15) }, enabled) {
                    TimePickerDialog(context, { _, h, m -> viewModel.setStart(LocalTime.of(h, m)) }, c.start.hour, c.start.minute, true).show()
                }
                Stepper(stringResource(R.string.reservation_duration), "%d:%02d".format(c.durationMinutes / 60, c.durationMinutes % 60),
                    { viewModel.shiftDuration(-30) }, { viewModel.shiftDuration(30) }, enabled)
                Stepper(stringResource(R.string.open_guests), c.partySize.toString(), { viewModel.setParty(c.partySize - 1) }, { viewModel.setParty(c.partySize + 1) }, enabled)
                OutlinedTextField(c.name, viewModel::setName, label = { Text(stringResource(R.string.reservation_name)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    c.phone, viewModel::setPhone, label = { Text(stringResource(R.string.reservation_phone)) }, singleLine = true, enabled = enabled,
                    isError = c.phone.length > 3 && !isValidPhone(c.phone),
                    supportingText = { Text(stringResource(R.string.reservation_phone_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(c.notes, viewModel::setNotes, label = { Text(stringResource(R.string.order_notes)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.confirmNow, onCheckedChange = viewModel::setConfirmNow, enabled = enabled)
                    Text(stringResource(R.string.reservation_confirm_now))
                }
                TextButton(onClick = viewModel::checkAvailability, enabled = enabled && !c.checking) { Text(stringResource(R.string.reservation_check)) }
                if (c.checking) CircularProgressIndicator()
                c.availability?.let { a ->
                    if (a.available) {
                        Text(stringResource(R.string.reservation_available), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.reservation_unavailable), color = MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            a.alternativeStartTimes.forEach { iso ->
                                localTime(iso, zone)?.let { t -> FilterChip(selected = false, onClick = { viewModel.setStart(t) }, label = { Text(t.format(HOUR)) }) }
                            }
                        }
                    }
                }
                c.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmCreate, enabled = c.canSubmit) { Text(stringResource(R.string.reservation_create)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissCreate, enabled = enabled) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeatDialog(s: SeatState, state: ReservationsUiState, canOverride: Boolean, viewModel: ReservationsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissSeat,
        title = { Text(stringResource(R.string.reservation_seat_title, s.reservation.customer.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.table_capacity, s.reservation.partySize, s.reservation.partySize))
                when {
                    s.loading -> CircularProgressIndicator()
                    s.options.isEmpty() -> Text(stringResource(R.string.reservation_no_options), color = MaterialTheme.colorScheme.error)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.options.forEachIndexed { i, o ->
                            FilterChip(
                                selected = s.selected == i,
                                onClick = { viewModel.selectOption(i) },
                                enabled = !s.submitting,
                                label = { Text(tablesText(state.tableNumbers(o.tableIds)) + " (${o.totalCapacity})") },
                            )
                        }
                    }
                }
                if (s.priorityConflict) {
                    if (canOverride) {
                        Text(stringResource(R.string.reservation_priority_override), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(s.overrideReason, viewModel::setOverrideReason, label = { Text(stringResource(R.string.cancel_reason)) },
                            enabled = !s.submitting, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.reservation_priority_ask_manager), color = MaterialTheme.colorScheme.error)
                    }
                }
                // Con conflicto de prioridad, la explicación de arriba ya lo dice: el mensaje del servidor sobraría.
                s.failure?.takeUnless { s.priorityConflict }?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmSeat, enabled = s.canSubmit) { Text(stringResource(R.string.reservation_seat)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissSeat, enabled = !s.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
private fun CancelDialog(c: CancelReservationState, viewModel: ReservationsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCancel,
        title = { Text(stringResource(R.string.reservation_cancel_title, c.reservation.customer.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(c.reason, viewModel::setCancelReason, label = { Text(stringResource(R.string.cancel_reason)) },
                    supportingText = { Text(stringResource(R.string.cancel_reason_hint, 3)) }, enabled = !c.submitting, modifier = Modifier.fillMaxWidth())
                c.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmCancel, enabled = c.canSubmit) { Text(stringResource(R.string.reservation_cancel)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissCancel, enabled = !c.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationsScreen(
    viewModel: ReservationsViewModel,
    can: ReservationPermissions,
    onBack: () -> Unit,
    onSeated: (sessionId: String, tableNumber: Int) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(8_000)
            viewModel.clearNotice()
        }
    }
    LaunchedEffect(state.seated) {
        state.seated?.let {
            viewModel.consumeSeated()
            onSeated(it.sessionId, it.tableNumber)
        }
    }
    state.creating?.let { CreateDialog(it, zone, viewModel) }
    state.seating?.let { SeatDialog(it, state, can.overridePriority, viewModel) }
    state.cancelling?.let { CancelDialog(it, viewModel) }
    val today = LocalDate.now(zone)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reservations_title)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.order_back)) } },
            )
        },
        bottomBar = {
            if (can.create) {
                Button(onClick = viewModel::startCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.reservation_new))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.moveDay(-1) }) { Text("‹") }
                Text(
                    when (state.day) {
                        today -> stringResource(R.string.reservation_today, state.day.format(DAY))
                        today.plusDays(1) -> stringResource(R.string.reservation_tomorrow, state.day.format(DAY))
                        else -> state.day.format(DAY)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.moveDay(1) }) { Text("›") }
            }
            state.failure?.let {
                Text(stringResource(if (state.loaded) R.string.tables_stale else R.string.reservations_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            state.actionFailure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            state.notice?.let {
                Text(if (it.arg != null) stringResource(it.text, it.arg) else stringResource(it.text), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.queue.isNotEmpty()) {
                        item { Text(stringResource(R.string.reservation_queue, state.queue.size), style = MaterialTheme.typography.titleSmall) }
                        items(state.queue, key = { "fila-" + it.reservation.id }) { entry ->
                            ReservationCard(entry.reservation, state, can, zone, entry.position, viewModel)
                        }
                    }
                    val rest = state.reservations.filter { r -> state.queue.none { it.reservation.id == r.id } }
                    item { Text(stringResource(R.string.reservation_of_day), style = MaterialTheme.typography.titleSmall) }
                    if (rest.isEmpty()) {
                        item { Text(stringResource(if (state.queue.isEmpty()) R.string.reservations_empty else R.string.reservations_only_waiting)) }
                    }
                    items(rest, key = { it.id }) { r -> ReservationCard(r, state, can, zone, null, viewModel) }
                }
            }
        }
    }
}
