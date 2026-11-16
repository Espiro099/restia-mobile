package com.restia.mobile.ui.cash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.CashMovementDto
import com.restia.mobile.core.network.CashShiftSummaryDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.core.network.RefundDto
import com.restia.mobile.data.cash.CASH_CURRENCY
import com.restia.mobile.data.cash.RegisterShift
import com.restia.mobile.ui.checks.amountOf
import com.restia.mobile.ui.checks.methodLabel
import com.restia.mobile.ui.checks.paymentStatusLabel
import com.restia.mobile.ui.orders.moneyText
import com.restia.mobile.ui.reservations.fieldMessage
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es-MX"))

private fun money(m: MoneyDto): String = moneyText(amountOf(m), m.currency)

private fun money(v: BigDecimal): String = moneyText(v, CASH_CURRENCY)

private fun localTime(iso: String?): String? =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrNull() }

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 429 && failure.retryAfterSeconds != null -> {
        val seconds = failure.retryAfterSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.error_too_many, seconds, seconds)
    }
    failure is ApiFailure.Server && failure.code == "VERSION_CONFLICT" -> stringResource(R.string.cash_conflict)
    failure is ApiFailure.Server && failure.code == "CASH_SHIFT_HAS_UNRESOLVED_OPERATIONS" -> stringResource(R.string.cash_unresolved_block)
    failure is ApiFailure.Server && failure.status == 403 -> failure.message ?: stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server && failure.status == 422 -> fieldMessage(failure) ?: failure.message ?: stringResource(R.string.error_unexpected)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun AmountField(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(16)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReasonField(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(500)) },
        label = { Text(stringResource(label)) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(enabled: Boolean, submitting: Boolean, label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(label))
    }
}

@Composable
private fun OpenDialog(form: OpenForm, viewModel: CashViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissOpen,
        title = { Text(stringResource(R.string.cash_open_title, form.register.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cash_open_hint), style = MaterialTheme.typography.bodySmall)
                AmountField(form.amount, R.string.cash_opening_amount, !form.submitting) { v -> viewModel.editOpen { it.copy(amount = v) } }
                if (form.needsReason) {
                    val difference = form.difference?.takeIf { it.isNotBlank() }?.let { runCatching { money(BigDecimal(it)) }.getOrNull() }
                    Text(
                        if (difference != null) stringResource(R.string.cash_opening_difference, difference) else stringResource(R.string.cash_opening_difference_unknown),
                        color = MaterialTheme.colorScheme.error,
                    )
                    ReasonField(form.reason, R.string.cash_reason, !form.submitting) { v -> viewModel.editOpen { it.copy(reason = v) } }
                }
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SubmitButton(form.canSubmit, form.submitting, R.string.cash_open_confirm, viewModel::submitOpen) },
        dismissButton = { TextButton(onClick = viewModel::dismissOpen, enabled = !form.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MoveDialog(form: MoveForm, viewModel: CashViewModel) {
    val deposit = form.type == "DEPOSIT"
    AlertDialog(
        onDismissRequest = viewModel::dismissMove,
        title = { Text(stringResource(if (deposit) R.string.cash_deposit else R.string.cash_withdrawal)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(form.amount, R.string.cash_amount, !form.submitting) { v -> viewModel.editMove { it.copy(amount = v) } }
                ReasonField(form.reason, R.string.cash_reason, !form.submitting) { v -> viewModel.editMove { it.copy(reason = v) } }
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SubmitButton(form.canSubmit, form.submitting, R.string.cash_move_confirm, viewModel::submitMove) },
        dismissButton = { TextButton(onClick = viewModel::dismissMove, enabled = !form.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CloseDialog(form: CloseForm, viewModel: CashViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissClose,
        title = { Text(stringResource(R.string.cash_close_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cash_expected, money(form.expected)), fontWeight = FontWeight.SemiBold)
                AmountField(form.counted, R.string.cash_counted, !form.submitting) { v -> viewModel.editClose { it.copy(counted = v) } }
                form.difference?.let { d ->
                    Text(
                        when {
                            d.signum() == 0 -> stringResource(R.string.cash_difference_none)
                            d.signum() > 0 -> stringResource(R.string.cash_difference_over, money(d))
                            else -> stringResource(R.string.cash_difference_short, money(d.negate()))
                        },
                        color = if (d.signum() == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                AmountField(form.left, R.string.cash_left_in_drawer, !form.submitting) { v -> viewModel.editClose { it.copy(left = v) } }
                Text(stringResource(R.string.cash_left_hint), style = MaterialTheme.typography.bodySmall)
                if (form.leftTooHigh) Text(stringResource(R.string.cash_left_too_high), color = MaterialTheme.colorScheme.error)
                if (form.needsReason) {
                    ReasonField(form.reason, R.string.cash_difference_reason, !form.submitting) { v -> viewModel.editClose { it.copy(reason = v) } }
                }
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SubmitButton(form.canSubmit, form.submitting, R.string.cash_close_confirm, viewModel::submitClose) },
        dismissButton = { TextButton(onClick = viewModel::dismissClose, enabled = !form.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CancelDialog(form: CancelForm, viewModel: CashViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCancel,
        title = { Text(stringResource(R.string.cash_cancel_closing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cash_cancel_closing_body))
                ReasonField(form.reason, R.string.cash_reason, !form.submitting, viewModel::editCancel)
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { SubmitButton(form.canSubmit, form.submitting, R.string.cash_cancel_closing_confirm, viewModel::submitCancel) },
        dismissButton = { TextButton(onClick = viewModel::dismissCancel, enabled = !form.submitting) { Text(stringResource(R.string.action_back)) } },
    )
}

@Composable
private fun StartClosingDialog(state: CashUiState, viewModel: CashViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissStartClosing,
        title = { Text(stringResource(R.string.cash_start_closing_title)) },
        text = { Text(stringResource(R.string.cash_start_closing_body)) },
        confirmButton = { SubmitButton(!state.working, state.working, R.string.cash_start_closing_confirm, viewModel::confirmStartClosing) },
        dismissButton = { TextButton(onClick = viewModel::dismissStartClosing, enabled = !state.working) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OutcomeChoice(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column {
        listOf("CONFIRMED" to R.string.cash_outcome_confirmed, "FAILED" to R.string.cash_outcome_failed).forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
            ) {
                RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
            }
        }
    }
}

@Composable
private fun ResolveDialog(form: ResolveForm, viewModel: CashViewModel) {
    val title = when (form.kind) {
        ResolveKind.RECONCILE_PAYMENT -> R.string.cash_reconcile_payment_title
        ResolveKind.REFUND -> R.string.cash_refund_title
        ResolveKind.REFUND_RESULT -> R.string.cash_refund_result_title
        ResolveKind.RECONCILE_REFUND -> R.string.cash_reconcile_refund_title
    }
    val amount = form.payment?.let { money(it.amount) + tipSuffix(it.tip) } ?: form.refund?.let { money(it.amount) + tipSuffix(it.tip) } ?: ""
    val method = form.payment?.method
    AlertDialog(
        onDismissRequest = viewModel::dismissResolve,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((method?.let { stringResource(methodLabel(it)) + " · " } ?: "") + amount, fontWeight = FontWeight.SemiBold)
                when (form.kind) {
                    ResolveKind.REFUND -> Text(stringResource(R.string.cash_refund_body))
                    ResolveKind.RECONCILE_PAYMENT -> Text(stringResource(R.string.cash_reconcile_payment_body))
                    else -> Text(stringResource(R.string.cash_refund_resolution_body))
                }
                if (form.asksOutcome) OutcomeChoice(form.outcome, !form.submitting) { o -> viewModel.editResolve { it.copy(outcome = o) } }
                if (form.needsReference) {
                    OutlinedTextField(
                        value = form.reference,
                        onValueChange = { v -> viewModel.editResolve { it.copy(reference = v.take(120)) } },
                        label = { Text(stringResource(R.string.cash_reference)) },
                        singleLine = true,
                        enabled = !form.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (form.asksReason) ReasonField(form.reason, R.string.cash_reason, !form.submitting) { v -> viewModel.editResolve { it.copy(reason = v) } }
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            SubmitButton(form.canSubmit, form.submitting, if (form.kind == ResolveKind.REFUND) R.string.cash_refund_confirm else R.string.cash_resolve_confirm, viewModel::submitResolve)
        },
        dismissButton = { TextButton(onClick = viewModel::dismissResolve, enabled = !form.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun tipSuffix(tip: MoneyDto): String = if (amountOf(tip).signum() > 0) " " + stringResource(R.string.cash_plus_tip, money(tip)) else ""

@Composable
private fun PaymentRow(payment: PaymentDto, shiftOpen: Boolean, viewModel: CashViewModel) {
    val can = viewModel.can
    val alert = payment.status == "UNKNOWN" || payment.status == "PENDING"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(methodLabel(payment.method)) + " · " + money(payment.amount) + tipSuffix(payment.tip), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(paymentStatusLabel(payment.status)) + (if (payment.refundId != null) " · " + stringResource(R.string.cash_has_refund) else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (payment.status == "PENDING") Text(stringResource(R.string.cash_pending_from_check), style = MaterialTheme.typography.bodySmall)
            localTime(payment.createdAt)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        when {
            payment.status == "UNKNOWN" && can.reconcilePayments ->
                TextButton(onClick = { viewModel.startReconcilePayment(payment) }) { Text(stringResource(R.string.cash_reconcile)) }
            isRefundable(payment) && shiftOpen && can.refund ->
                TextButton(onClick = { viewModel.startRefund(payment) }) { Text(stringResource(R.string.cash_refund)) }
        }
    }
}

@Composable
private fun RefundRow(refund: RefundDto, viewModel: CashViewModel) {
    val can = viewModel.can
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.cash_refund_of, money(refund.amount) + tipSuffix(refund.tip)), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(if (refund.status == "PENDING") R.string.cash_refund_status_pending else R.string.cash_refund_status_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(refund.reason, style = MaterialTheme.typography.bodySmall)
        }
        val allowed = (refund.status == "PENDING" && can.refundResult) || (refund.status == "UNKNOWN" && can.reconcileRefunds)
        if (allowed) {
            TextButton(onClick = { viewModel.startRefundResolution(refund) }) {
                Text(stringResource(if (refund.status == "PENDING") R.string.cash_record_result else R.string.cash_reconcile))
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.SemiBold else null)
        Text(value, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

@Composable
private fun SummaryCard(summary: CashShiftSummaryDto, closing: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryLine(stringResource(R.string.cash_opening_amount), money(summary.openingAmount))
            summary.byMethod.forEach { m ->
                val sales = amountOf(m.sales).add(amountOf(m.tips))
                val refunds = amountOf(m.refunds).add(amountOf(m.tipsRefunded))
                if (sales.signum() != 0 || refunds.signum() != 0) {
                    SummaryLine(stringResource(R.string.cash_sales_of, stringResource(methodLabel(m.method))), money(sales))
                    if (refunds.signum() != 0) SummaryLine(stringResource(R.string.cash_refunds_of, stringResource(methodLabel(m.method))), "−" + money(refunds))
                }
            }
            SummaryLine(stringResource(R.string.cash_deposits), money(summary.deposits))
            SummaryLine(stringResource(R.string.cash_withdrawals), "−" + money(summary.withdrawals))
            HorizontalDivider()
            SummaryLine(stringResource(if (closing) R.string.cash_expected_closing else R.string.cash_expected_now), money(summary.expectedCash), bold = true)
            if (summary.unresolved.total > 0) {
                Text(
                    pluralStringResource(R.plurals.cash_unresolved, summary.unresolved.total, summary.unresolved.total),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MovementRow(m: CashMovementDto) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(if (m.type == "DEPOSIT") R.string.cash_deposit else R.string.cash_withdrawal), fontWeight = FontWeight.SemiBold)
            Text(m.reason, style = MaterialTheme.typography.bodySmall)
            localTime(m.createdAt)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text((if (m.type == "DEPOSIT") "" else "−") + money(m.amount))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftDetailScreen(state: CashUiState, detail: ShiftDetail, viewModel: CashViewModel) {
    val can = viewModel.can
    val open = detail.shift.status == "OPEN"
    val closing = detail.shift.status == "CLOSING"
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(detail.register.name) },
                navigationIcon = { TextButton(onClick = viewModel::closeDetail, enabled = !state.working) { Text(stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = viewModel::loadDetail, enabled = !detail.loading) { Text(stringResource(R.string.action_refresh)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(if (closing) R.string.cash_status_closing else R.string.cash_status_open) +
                        (localTime(detail.shift.openedAt)?.let { " · " + stringResource(R.string.cash_opened_at, it) } ?: ""),
                    color = if (closing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (closing) Text(stringResource(R.string.cash_closing_hint), style = MaterialTheme.typography.bodySmall)
            }
            detail.failure?.let { item { Text(stringResource(R.string.tables_stale) + " " + failureText(it), color = MaterialTheme.colorScheme.error) } }
            state.actionFailure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { n -> item { Text(stringResource(n.text, n.arg ?: "")) } }
            item {
                when (val summary = detail.summary) {
                    null -> if (detail.loading) Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                    else -> SummaryCard(summary, closing)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (open && can.move) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { viewModel.startMove("DEPOSIT") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cash_deposit)) }
                            OutlinedButton(onClick = { viewModel.startMove("WITHDRAWAL") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cash_withdrawal)) }
                        }
                    }
                    if (open && can.close) {
                        Button(onClick = viewModel::askStartClosing, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cash_start_closing))
                        }
                    }
                    if (closing && can.close) {
                        Button(
                            onClick = viewModel::startClose,
                            enabled = !state.working && detail.summary != null && detail.unresolved == 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cash_close)) }
                        if (detail.unresolved > 0) Text(stringResource(R.string.cash_unresolved_block), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = viewModel::startCancel, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cash_cancel_closing))
                        }
                    }
                }
            }
            if (detail.refunds.isNotEmpty()) {
                item { Text(stringResource(R.string.cash_refunds_to_resolve), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(detail.refunds, key = { "r-" + it.id }) { RefundRow(it, viewModel) }
            }
            if (can.readPayments) {
                // Lo que traba el corte, primero.
                val payments = detail.payments.sortedBy { if (it.status == "UNKNOWN" || it.status == "PENDING") 0 else 1 }
                item { Text(stringResource(R.string.cash_payments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                if (payments.isEmpty() && !detail.loading) item { Text(stringResource(R.string.cash_no_payments), style = MaterialTheme.typography.bodySmall) }
                items(payments, key = { "p-" + it.id }) { PaymentRow(it, open, viewModel) }
            }
            item { Text(stringResource(R.string.cash_movements), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (detail.movements.isEmpty() && !detail.loading) item { Text(stringResource(R.string.cash_no_movements), style = MaterialTheme.typography.bodySmall) }
            items(detail.movements, key = { it.id }) { MovementRow(it) }
        }
    }
}

@Composable
private fun RegisterCard(row: RegisterShift, can: CashPermissions, viewModel: CashViewModel) {
    val shift = row.shift
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = shift != null) { viewModel.view(row) }) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.register.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (shift?.status) {
                        "OPEN" -> stringResource(R.string.cash_status_open) + (localTime(shift.openedAt)?.let { " · " + stringResource(R.string.cash_opened_at, it) } ?: "")
                        "CLOSING" -> stringResource(R.string.cash_status_closing)
                        else -> stringResource(R.string.cash_status_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (shift?.status == "CLOSING") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                shift != null -> TextButton(onClick = { viewModel.view(row) }) { Text(stringResource(R.string.cash_view)) }
                can.open -> Button(onClick = { viewModel.startOpen(row.register) }) { Text(stringResource(R.string.cash_open)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashScreen(viewModel: CashViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    state.opening?.let { OpenDialog(it, viewModel) }
    state.moving?.let { MoveDialog(it, viewModel) }
    state.closing?.let { CloseDialog(it, viewModel) }
    state.cancelling?.let { CancelDialog(it, viewModel) }
    state.resolving?.let { ResolveDialog(it, viewModel) }
    if (state.confirmingStart) StartClosingDialog(state, viewModel)
    val detail = state.detail
    if (detail != null) {
        ShiftDetailScreen(state, detail, viewModel)
        return
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cash_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text(stringResource(R.string.action_refresh)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.cash_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.notice?.let { n ->
                Text(stringResource(n.text, n.arg ?: ""), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { viewModel.clearNotice() })
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.registers.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.cash_no_registers)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.registers, key = { it.register.id }) { RegisterCard(it, viewModel.can, viewModel) }
                }
            }
        }
    }
}
