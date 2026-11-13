package com.restia.mobile.ui.checks

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.ui.orders.moneyText
import java.math.BigDecimal
import kotlinx.coroutines.delay

@StringRes
fun checkStatusLabel(status: String): Int = when (status) {
    "OPEN" -> R.string.check_open
    "REQUESTED" -> R.string.check_requested
    "PARTIALLY_PAID" -> R.string.check_partially_paid
    "PAID" -> R.string.check_paid
    "CANCELLED" -> R.string.check_cancelled
    "REFUNDED" -> R.string.check_refunded
    else -> R.string.check_unknown
}

@StringRes
fun methodLabel(method: String): Int = when (method) {
    "CASH" -> R.string.method_cash
    "CARD_CREDIT" -> R.string.method_credit
    "CARD_DEBIT" -> R.string.method_debit
    "TRANSFER" -> R.string.method_transfer
    else -> R.string.method_other
}

@StringRes
fun paymentStatusLabel(status: String): Int = when (status) {
    "PENDING" -> R.string.payment_pending
    "CONFIRMED" -> R.string.payment_confirmed
    "FAILED" -> R.string.payment_failed
    "CANCELLED" -> R.string.payment_cancelled
    "UNKNOWN" -> R.string.payment_unknown
    else -> R.string.check_unknown
}

/** Red y servidor tienen texto propio; los conflictos de cobro e impresión traen un mensaje claro del backend. */
@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 403 -> stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

private fun money(m: MoneyDto): String = moneyText(amountOf(m), m.currency)

private fun money(v: BigDecimal, currency: String): String = moneyText(v, currency)

@Composable
private fun AmountRow(label: String, value: String, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (strong) FontWeight.Bold else null)
        Text(value, fontWeight = if (strong) FontWeight.Bold else null)
    }
}

@Composable
private fun PaymentRow(payment: PaymentDto, canRecord: Boolean, canPrint: Boolean, busy: Boolean, viewModel: CheckViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            val brand = payment.card?.brand?.let { " $it" } ?: ""
            Text(stringResource(methodLabel(payment.method)) + brand + " · " + money(payment.amount))
            if (amountOf(payment.tip).signum() > 0) Text(stringResource(R.string.payment_tip, money(payment.tip)), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(paymentStatusLabel(payment.status)),
                style = MaterialTheme.typography.bodySmall,
                color = if (payment.status == "UNKNOWN" || payment.status == "PENDING") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRecord && payment.status == "PENDING") {
            OutlinedButton(onClick = { viewModel.startResult(payment) }, enabled = !busy) { Text(stringResource(R.string.result_action)) }
        }
        if (canPrint && payment.status == "CONFIRMED") {
            TextButton(onClick = { viewModel.print(CheckViewModel.PAYMENT_RECEIPT, payment.id) }, enabled = !busy) { Text(stringResource(R.string.print_receipt)) }
        }
    }
}

data class CheckPermissions(val request: Boolean, val pay: Boolean, val record: Boolean, val print: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckCard(check: CheckDto, payments: List<PaymentDto>, can: CheckPermissions, busy: Boolean, viewModel: CheckViewModel) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = stringResource(R.string.check_title, check.number) + (check.label?.let { " · $it" } ?: "")
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(checkStatusLabel(check.status)), style = MaterialTheme.typography.bodySmall)
            // Igual que la precuenta: lo cancelado o anulado no se cobra y no aparece.
            val billed = check.units.filter { it.status != "CANCELLED" && it.status != "VOIDED" }
            billed.groupBy { it.productName to it.price.amount }.forEach { (key, rows) ->
                val total = amountOf(rows.first().price).multiply(BigDecimal(rows.size))
                AmountRow(rows.size.toString() + " × " + key.first, money(total, rows.first().price.currency))
            }
            billed.mapNotNull { u -> u.adjustment?.let { u.productName to it } }.forEach { (name, adj) ->
                AmountRow(stringResource(R.string.check_courtesy, name), "-" + money(adj.amount))
            }
            if (billed.isEmpty()) Text(stringResource(R.string.check_empty), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            AmountRow(stringResource(R.string.check_subtotal), money(check.subtotal))
            AmountRow(stringResource(R.string.check_tax), money(check.taxTotal))
            if (amountOf(check.adjustmentTotal).signum() != 0) AmountRow(stringResource(R.string.check_courtesies), "-" + money(check.adjustmentTotal))
            AmountRow(stringResource(R.string.check_total), money(check.total), strong = true)
            if (amountOf(check.paidTotal).signum() != 0) AmountRow(stringResource(R.string.check_paid_total), money(check.paidTotal))
            if (amountOf(check.pendingTotal).signum() != 0) AmountRow(stringResource(R.string.check_pending_total), money(check.pendingTotal))
            AmountRow(stringResource(R.string.check_balance), money(check.balance), strong = true)
            if (payments.isNotEmpty()) {
                HorizontalDivider()
                payments.forEach { PaymentRow(it, can.record, can.print, busy, viewModel) }
            }
            // Cobrar es la acción principal: ancho completo, nunca cortada por las demás.
            if (can.pay && isPayable(check)) {
                Button(onClick = { viewModel.startPay(check) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pay_action)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (can.request && check.status == "OPEN" && billed.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.requestCheck(check) }, enabled = !busy) { Text(stringResource(R.string.check_request)) }
                }
                if (can.print && check.status != "CANCELLED" && billed.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.print(CheckViewModel.PRE_CHECK, check.id) }, enabled = !busy) { Text(stringResource(R.string.print_pre_check)) }
                }
            }
        }
    }
}

@Composable
private fun AmountField(value: String, onChange: (String) -> Unit, @StringRes label: Int, enabled: Boolean, error: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayDialog(state: PayState, shifts: List<com.restia.mobile.data.checks.ShiftOption>?, shiftsFailure: ApiFailure?, viewModel: CheckViewModel) {
    val currency = state.check.balance.currency
    val enabled = !state.submitting
    AlertDialog(
        onDismissRequest = viewModel::dismissPay,
        title = { Text(stringResource(R.string.pay_title, state.check.number)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pay_available, money(available(state.check), currency)))
                when {
                    shifts == null && shiftsFailure != null -> {
                        Text(failureText(shiftsFailure), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retryShifts) { Text(stringResource(R.string.action_retry)) }
                    }
                    shifts == null -> CircularProgressIndicator()
                    shifts.isEmpty() -> Text(stringResource(R.string.pay_no_shift), color = MaterialTheme.colorScheme.error)
                    shifts.size > 1 -> {
                        Text(stringResource(R.string.pay_register))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            shifts.forEach { s ->
                                FilterChip(
                                    selected = state.shiftId == s.id,
                                    onClick = { viewModel.setShift(s.id) },
                                    enabled = enabled,
                                    label = { Text(s.registerName.ifEmpty { stringResource(R.string.pay_register_unnamed) }) },
                                )
                            }
                        }
                    }
                }
                // Los cuatro métodos a la vista: en una fila desplazable, Transferencia quedaba fuera.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    METHODS.forEach { m ->
                        FilterChip(selected = state.method == m, onClick = { viewModel.setMethod(m) }, enabled = enabled, label = { Text(stringResource(methodLabel(m))) })
                    }
                }
                AmountField(state.amount, viewModel::setAmount, R.string.pay_amount, enabled, error = state.amountValue == null || state.exceeds)
                if (state.exceeds) Text(stringResource(R.string.pay_exceeds), color = MaterialTheme.colorScheme.error)
                AmountField(state.tip, viewModel::setTip, R.string.pay_tip, enabled, error = state.tipValue == null)
                if (state.method == CASH) {
                    AmountField(state.tendered, viewModel::setTendered, R.string.pay_tendered, enabled, error = state.tendered.isNotEmpty() && state.change == null)
                    state.change?.let { Text(stringResource(R.string.pay_change, money(it, currency)), style = MaterialTheme.typography.titleMedium) }
                } else {
                    Text(stringResource(R.string.pay_terminal_hint), style = MaterialTheme.typography.bodySmall)
                }
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmPay, enabled = state.canConfirm) {
                Text(stringResource(if (state.method == CASH) R.string.pay_confirm_cash else R.string.pay_confirm_other))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissPay, enabled = enabled) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
private fun ResultDialog(state: ResultState, viewModel: CheckViewModel) {
    val enabled = !state.submitting
    AlertDialog(
        onDismissRequest = viewModel::dismissResult,
        title = { Text(stringResource(R.string.result_title, stringResource(methodLabel(state.payment.method)), money(state.payment.amount))) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (state.isCard) R.string.result_body_card else R.string.result_body_transfer))
                if (state.isCard) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        BRANDS.forEach { b ->
                            FilterChip(
                                selected = state.brand == b,
                                onClick = { viewModel.setBrand(b) },
                                enabled = enabled,
                                label = { Text(if (b == "OTHER") stringResource(R.string.result_brand_other) else b) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.reference,
                    onValueChange = viewModel::setReference,
                    label = { Text(stringResource(R.string.result_reference)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isCard) {
                    OutlinedTextField(
                        value = state.last4,
                        onValueChange = viewModel::setLast4,
                        label = { Text(stringResource(R.string.result_last4)) },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { viewModel.recordResult("FAILED") }, enabled = state.canReject) { Text(stringResource(R.string.result_failed)) }
                    TextButton(onClick = { viewModel.recordResult("CANCELLED") }, enabled = state.canReject) { Text(stringResource(R.string.result_cancelled)) }
                    TextButton(onClick = { viewModel.recordResult("UNKNOWN") }, enabled = state.canReject) { Text(stringResource(R.string.result_unknown)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.recordResult("CONFIRMED") }, enabled = state.canConfirm) { Text(stringResource(R.string.result_confirmed)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissResult, enabled = enabled) { Text(stringResource(R.string.result_later)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(viewModel: CheckViewModel, tableNumber: Int, can: CheckPermissions, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(6_000)
            viewModel.clearNotice()
        }
    }
    state.paying?.let { PayDialog(it, state.shifts, state.shiftsFailure, viewModel) }
    state.recording?.let { ResultDialog(it, viewModel) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check_screen_title, tableNumber)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.check_back)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.check_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.actionFailure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            state.notice?.let {
                Text(
                    if (it.arg != null) stringResource(it.text, it.arg) else stringResource(it.text),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.checks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.check_none)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.checks, key = { it.id }) { check ->
                        CheckCard(check, state.payments[check.id].orEmpty(), can, state.busy, viewModel)
                    }
                }
            }
        }
    }
}
