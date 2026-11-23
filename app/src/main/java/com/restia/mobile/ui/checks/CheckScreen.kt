package com.restia.mobile.ui.checks

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.PaymentDto
import com.restia.mobile.ui.orders.moneyText
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.FilterTag
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.OptionRow
import com.restia.mobile.ui.theme.PrimaryButton
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import com.restia.mobile.ui.theme.readableWidth
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}

/** Suma un importe de las cuentas que aún cuentan (ni reembolsadas ni canceladas); null si las monedas no coinciden. */
private fun tableSum(checks: List<CheckDto>, amount: (CheckDto) -> MoneyDto): MoneyDto? {
    val open = checks.filter { it.status !in setOf("REFUNDED", "CANCELLED") }
    val currency = open.map { amount(it).currency }.distinct().singleOrNull() ?: return null
    val sum = open.fold(BigDecimal.ZERO) { acc, c -> acc + amountOf(amount(c)) }
    return MoneyDto(sum.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), currency)
}

/** Lo que falta por cobrar en la mesa, sumado entre sus cuentas. */
fun tableBalance(checks: List<CheckDto>): MoneyDto? = tableSum(checks) { it.balance }

/** Lo que suma la mesa, para cuando ya no falta nada por cobrar. */
fun tableTotal(checks: List<CheckDto>): MoneyDto? = tableSum(checks) { it.total }

/** Color del estado de la cuenta; el texto siempre lo dice. */
@Composable
private fun checkTone(status: String): Color = when (status) {
    "OPEN" -> Restia.colors.free
    "REQUESTED" -> Restia.colors.attention
    "PARTIALLY_PAID" -> Restia.colors.busy
    "PAID" -> Restia.colors.heading
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun PaymentRow(payment: PaymentDto, canRecord: Boolean, canPrint: Boolean, busy: Boolean, viewModel: CheckViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val brand = payment.card?.brand?.let { " $it" } ?: ""
            Text(
                stringResource(methodLabel(payment.method)) + brand + " · " + money(payment.amount),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (amountOf(payment.tip).signum() > 0) {
                Text(stringResource(R.string.payment_tip, money(payment.tip)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(paymentStatusLabel(payment.status)) + if (payment.refundId != null) " · " + stringResource(R.string.payment_refunded) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (payment.status == "UNKNOWN" || payment.status == "PENDING") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRecord && payment.status == "PENDING") {
            TextButton(onClick = { viewModel.startResult(payment) }, enabled = !busy) { Text(stringResource(R.string.result_action)) }
        }
        if (canPrint && payment.status == "CONFIRMED") {
            TextButton(onClick = { viewModel.print(CheckViewModel.PAYMENT_RECEIPT, payment.id) }, enabled = !busy) { Text(stringResource(R.string.print_receipt)) }
        }
    }
}

data class CheckPermissions(
    val request: Boolean,
    val pay: Boolean,
    val record: Boolean,
    val print: Boolean,
    /** checks.move_units y checks.create: separar platillos, a otra cuenta o a una nueva. */
    val move: Boolean = false,
    val create: Boolean = false,
    val cancel: Boolean = false,
)

/** Método de pago como tarjeta elegible (propuesta 05): círculo, ícono y nombre; la elegida, con borde naranja. */
@Composable
private fun MethodCard(method: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Icon(painterResource(R.drawable.ic_card), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            stringResource(methodLabel(method)),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
        )
    }
}

/** El total en grande (propuesta 05): el importe a 44 y la moneda, más chica, a su lado. */
@Composable
private fun BigMoney(amount: BigDecimal, currency: String) {
    val text = buildAnnotatedString {
        append("$" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
        withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)) { append(" $currency") }
    }
    Text(text, style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 52.sp), color = Restia.colors.heading)
}

/** Fila de importe: concepto a la izquierda, monto a la derecha. */
@Composable
private fun MoneyRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) {
            TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)) }
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = valueColor)
        }
    }
}

/** Cuántos platillos cobra la cuenta ("4 platillos"). */
@Composable
private fun itemsLine(check: CheckDto): String {
    val count = check.units.count { it.status != "CANCELLED" && it.status != "VOIDED" }
    return pluralStringResource(R.plurals.check_items, count, count)
}

/** Las acciones de una cuenta que no son cobrar: van en su hoja, para no saturar la pantalla. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckActionsSheet(check: CheckDto, can: CheckPermissions, viewModel: CheckViewModel, onDismiss: () -> Unit) {
    val billed = check.units.any { it.status != "CANCELLED" && it.status != "VOIDED" }
    val actions = buildList<Pair<Int, () -> Unit>> {
        if (can.request && check.status == "OPEN" && billed) add(R.string.check_request to { viewModel.requestCheck(check) })
        if (can.move && canMoveFrom(check)) add(R.string.move_action to { viewModel.startMove(check) })
        if (canAdjust(check) && courtesyCandidates(check).isNotEmpty()) add(R.string.courtesy_action to { viewModel.startAction(CheckActionKind.COURTESY, check) })
        if (canReopen(check)) add(R.string.check_reopen to { viewModel.startAction(CheckActionKind.REOPEN, check) })
        if (can.cancel && canCancelCheck(check)) add(R.string.check_cancel_empty to { viewModel.startAction(CheckActionKind.CANCEL, check) })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.check_title, check.number) + (check.label?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                color = Restia.colors.heading,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            actions.forEach { (label, action) ->
                OptionRow(R.drawable.ic_receipt, stringResource(label), tint = MaterialTheme.colorScheme.onSurface, onClick = {
                    onDismiss()
                    action()
                })
            }
        }
    }
}

/** Hay algo más que hacer con la cuenta, además de cobrar e imprimir. */
private fun hasMoreActions(check: CheckDto, can: CheckPermissions): Boolean {
    val billed = check.units.any { it.status != "CANCELLED" && it.status != "VOIDED" }
    return (can.request && check.status == "OPEN" && billed) || (can.move && canMoveFrom(check)) ||
        (canAdjust(check) && courtesyCandidates(check).isNotEmpty()) || canReopen(check) || (can.cancel && canCancelCheck(check))
}

/**
 * Una cuenta (propuesta 05): estado, total por cobrar en grande, consumo y propina, el método de pago y el
 * paso a revisar el cobro. El desglose (platillos, IVA, cortesías) queda plegado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckSection(
    check: CheckDto,
    payments: List<PaymentDto>,
    can: CheckPermissions,
    busy: Boolean,
    showTitle: Boolean,
    viewModel: CheckViewModel,
) {
    var method by rememberSaveable(check.id) { mutableStateOf(CASH) }
    var expanded by rememberSaveable(check.id) { mutableStateOf(false) }
    var sheet by rememberSaveable(check.id) { mutableStateOf(false) }
    if (sheet) CheckActionsSheet(check, can, viewModel) { sheet = false }
    val payable = can.pay && isPayable(check)
    // Reembolsada o cancelada ya no se cobra: su saldo (que puede ser negativo) no significa nada para el mesero.
    val settled = check.status in setOf("REFUNDED", "CANCELLED") || amountOf(check.balance).signum() <= 0
    val billed = check.units.filter { it.status != "CANCELLED" && it.status != "VOIDED" }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTitle) {
                Text(
                    stringResource(R.string.check_title, check.number) + (check.label?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Restia.colors.heading,
                    modifier = Modifier.padding(end = 10.dp).semantics { heading() },
                )
            }
            StatusPill(stringResource(checkStatusLabel(check.status)), checkTone(check.status))
            Spacer(Modifier.weight(1f))
            if (hasMoreActions(check, can)) {
                IconButton(onClick = { sheet = true }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.check_more_actions, check.number), modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(
            stringResource(if (settled) R.string.check_total else R.string.check_total_due),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val due = available(check)
        if (settled) BigMoney(amountOf(check.total), check.total.currency) else BigMoney(due, check.balance.currency)
        Text(itemsLine(check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Separator()
        MoneyRow(stringResource(R.string.check_consumption), money(check.total))
        val tip = amountOf(check.tipTotal)
        MoneyRow(
            stringResource(R.string.check_tip),
            if (tip.signum() > 0) money(check.tipTotal) else stringResource(if (payable) R.string.check_tip_add else R.string.review_no_tip),
            onClick = if (tip.signum() == 0 && payable && !busy && available(check).signum() > 0) ({ viewModel.startPay(check); viewModel.setMethod(method) }) else null,
        )
        if (amountOf(check.paidTotal).signum() != 0) MoneyRow(stringResource(R.string.check_paid_total), money(check.paidTotal))
        if (amountOf(check.pendingTotal).signum() != 0) MoneyRow(stringResource(R.string.check_pending_total), money(check.pendingTotal))
        check.refundedTotal?.takeIf { amountOf(it).signum() != 0 }?.let { MoneyRow(stringResource(R.string.check_refunded_total), "-" + money(it)) }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (expanded) R.string.check_detail_hide else R.string.check_detail_show))
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Igual que la precuenta: lo cancelado o anulado no se cobra y no aparece.
                billed.groupBy { it.productName to it.price.amount }.forEach { (key, rows) ->
                    val total = amountOf(rows.first().price).multiply(BigDecimal(rows.size))
                    AmountRow(rows.size.toString() + " × " + key.first, money(total, rows.first().price.currency))
                }
                billed.filter { it.adjustment != null }.forEach { u ->
                    val adj = u.adjustment!!
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.check_courtesy, u.productName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        // Quitar la cortesía: con checks.adjust o con autorización de un gerente.
                        if (canAdjust(check)) {
                            TextButton(onClick = { viewModel.startAction(CheckActionKind.REVERT, check, u) }, enabled = !busy) { Text(stringResource(R.string.courtesy_revert)) }
                        }
                        Text("-" + money(adj.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (billed.isEmpty()) Text(stringResource(R.string.check_empty), style = MaterialTheme.typography.bodySmall)
                Separator()
                AmountRow(stringResource(R.string.check_subtotal), money(check.subtotal))
                AmountRow(stringResource(R.string.check_tax), money(check.taxTotal))
                if (amountOf(check.adjustmentTotal).signum() != 0) AmountRow(stringResource(R.string.check_courtesies), "-" + money(check.adjustmentTotal))
            }
        }
        Separator()
        if (payable) {
            Text(stringResource(R.string.check_method), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            METHODS.forEach { m -> MethodCard(m, method == m, !busy) { method = m } }
            Text(stringResource(R.string.check_tip_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (payments.isNotEmpty()) {
            Text(stringResource(R.string.payments_title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            payments.forEach { PaymentRow(it, can.record, can.print, busy, viewModel) }
        }
        if (payable) {
            // Con un pago en curso por todo el saldo no queda nada que cobrar hasta que se registre su resultado.
            PrimaryButton(
                stringResource(R.string.check_review_action, money(available(check), check.balance.currency)),
                onClick = {
                    viewModel.startPay(check)
                    viewModel.setMethod(method)
                },
                enabled = !busy && available(check).signum() > 0,
                modifier = Modifier.heightIn(min = 54.dp),
            )
        }
        if (can.print && check.status != "CANCELLED" && billed.isNotEmpty()) {
            TextButton(onClick = { viewModel.print(CheckViewModel.PRE_CHECK, check.id) }, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.print_pre_check), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
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
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Revisar cobro (propuesta 12): el importe en grande, consumo, importe y propina editables, el método elegido
 * y, en efectivo, lo recibido y el cambio. Confirmar registra el pago en la caja abierta.
 */
@Composable
private fun PayReview(
    state: PayState,
    shifts: List<com.restia.mobile.data.checks.ShiftOption>?,
    shiftsFailure: ApiFailure?,
    shiftsLoading: Boolean,
    viewModel: CheckViewModel,
) {
    val currency = state.check.balance.currency
    val enabled = !state.submitting
    BackHandler(onBack = viewModel::dismissPay)
    val due = (state.amountValue ?: BigDecimal.ZERO) + (state.tipValue ?: BigDecimal.ZERO)
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        BackHeader(stringResource(R.string.review_title), viewModel::dismissPay)
        Column(
            modifier = Modifier
                .weight(1f)
                .readableWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPill(stringResource(checkStatusLabel(state.check.status)), checkTone(state.check.status))
            Text(
                stringResource(R.string.review_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigMoney(due, currency)
            Text(itemsLine(state.check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Separator()
            MoneyRow(stringResource(R.string.check_consumption), money(state.check.total))
            AmountField(state.amount, viewModel::setAmount, R.string.pay_amount, enabled, error = state.amountValue == null || state.exceeds)
            Text(stringResource(R.string.pay_available, money(available(state.check), currency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.exceeds) Text(stringResource(R.string.pay_exceeds), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AmountField(state.tip, viewModel::setTip, R.string.pay_tip, enabled, error = state.tipValue == null)
            Separator()
            Text(stringResource(R.string.review_method), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            METHODS.forEach { m -> MethodCard(m, state.method == m, enabled) { viewModel.setMethod(m) } }
            if (state.method == CASH) {
                AmountField(state.tendered, viewModel::setTendered, R.string.pay_tendered, enabled, error = state.tendered.isNotEmpty() && state.change == null)
                state.change?.let { Text(stringResource(R.string.pay_change, money(it, currency)), style = MaterialTheme.typography.titleMedium) }
            } else {
                Text(stringResource(R.string.pay_terminal_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shifts != null && shifts.size > 1) {
                Text(stringResource(R.string.pay_register), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    shifts.forEach { shift ->
                        FilterTag(shift.registerName.ifEmpty { stringResource(R.string.pay_register_unnamed) }, state.shiftId == shift.id) {
                            if (enabled) viewModel.setShift(shift.id)
                        }
                    }
                }
            }
            Text(stringResource(R.string.check_tip_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier.readableWidth().navigationBarsPadding().padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Junto al botón: si no hay caja, el mesero ve por qué no puede cobrar.
            when {
                shiftsLoading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.pay_shift_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                shiftsFailure != null -> ShiftProblem(failureText(shiftsFailure), viewModel::retryShifts)
                shifts?.isEmpty() == true -> ShiftProblem(stringResource(R.string.pay_no_shift), viewModel::retryShifts)
                shifts != null && shifts.size > 1 && state.shiftId == null ->
                    Text(stringResource(R.string.pay_choose_register), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            PrimaryButton(
                stringResource(R.string.review_confirm, money(due, currency)),
                onClick = viewModel::confirmPay,
                enabled = state.canConfirm,
                busy = state.submitting,
                modifier = Modifier.heightIn(min = 54.dp),
            )
            TextButton(onClick = viewModel::dismissPay, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.review_back), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun ShiftProblem(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
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

@Composable
private fun Choice(selected: Boolean, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) { content() }
    }
}

@Composable
private fun MoveDialog(state: MoveUnitsState, checks: List<CheckDto>, canCreate: Boolean, viewModel: CheckViewModel) {
    val enabled = !state.submitting
    val targets = moveTargets(checks, state.source).filter { it.id != state.created?.id }
    AlertDialog(
        onDismissRequest = viewModel::dismissMove,
        title = { Text(stringResource(R.string.move_title, state.source.number)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.move_units), style = MaterialTheme.typography.labelLarge)
                billedUnits(state.source).forEach { u ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(value = u.unitId in state.selected, enabled = enabled, role = Role.Checkbox) { viewModel.toggleUnit(u.unitId) },
                    ) {
                        Checkbox(checked = u.unitId in state.selected, onCheckedChange = null, enabled = enabled)
                        Text(
                            u.productName + " · " + money(u.price) + (if (u.adjustment != null) " · " + stringResource(R.string.move_with_courtesy) else ""),
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.move_to), style = MaterialTheme.typography.labelLarge)
                targets.forEach { t ->
                    Choice(state.targetId == t.id, enabled, { viewModel.chooseTarget(t.id) }) {
                        Text(stringResource(R.string.check_title, t.number) + (t.label?.let { " · $it" } ?: ""))
                    }
                }
                state.created?.let { c ->
                    Choice(state.targetId == null || state.targetId == c.id, enabled, { viewModel.chooseTarget(c.id) }) {
                        Text(stringResource(R.string.check_title, c.number) + " · " + stringResource(R.string.move_just_created))
                    }
                }
                if (canCreate && state.created == null) {
                    Choice(state.targetId == null, enabled, { viewModel.chooseTarget(null) }) { Text(stringResource(R.string.move_new_check)) }
                    if (state.targetId == null) {
                        OutlinedTextField(
                            value = state.label,
                            onValueChange = viewModel::setNewLabel,
                            label = { Text(stringResource(R.string.move_new_label)) },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(stringResource(R.string.move_hint), style = MaterialTheme.typography.bodySmall)
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val ready = state.canSubmit && (state.targetId != null || canCreate || state.created != null)
            TextButton(onClick = viewModel::confirmMove, enabled = ready) {
                if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(pluralStringResource(R.plurals.move_confirm, state.selected.size, state.selected.size))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissMove, enabled = enabled) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
private fun ActionDialog(state: CheckAction, viewModel: CheckViewModel) {
    val enabled = !state.submitting
    val title = when (state.kind) {
        CheckActionKind.REOPEN -> R.string.check_reopen_title
        CheckActionKind.CANCEL -> R.string.check_cancel_title
        CheckActionKind.COURTESY -> R.string.courtesy_title
        CheckActionKind.REVERT -> R.string.courtesy_revert_title
    }
    val body = when (state.kind) {
        CheckActionKind.REOPEN -> R.string.check_reopen_body
        CheckActionKind.CANCEL -> R.string.check_cancel_body
        CheckActionKind.COURTESY -> R.string.courtesy_body
        CheckActionKind.REVERT -> R.string.courtesy_revert_body
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissAction,
        title = { Text(stringResource(title, state.check.number)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(body))
                if (state.kind == CheckActionKind.COURTESY) {
                    courtesyCandidates(state.check).forEach { u ->
                        Choice(state.unit?.unitId == u.unitId, enabled, { viewModel.chooseCourtesyUnit(u) }) { Text(u.productName + " · " + money(u.price)) }
                    }
                }
                if (state.kind == CheckActionKind.REVERT) state.unit?.let { Text(it.productName + " · " + money(it.price), fontWeight = FontWeight.SemiBold) }
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = viewModel::setActionReason,
                    label = { Text(stringResource(R.string.check_action_reason)) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.showsApprover) {
                    Text(stringResource(R.string.void_approver_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.check_approver_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = state.approver.email,
                        onValueChange = viewModel::setApproverEmail,
                        label = { Text(stringResource(R.string.void_approver_email)) },
                        singleLine = true,
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.approver.password,
                        onValueChange = viewModel::setApproverPassword,
                        label = { Text(stringResource(R.string.void_approver_password)) },
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmAction, enabled = state.canSubmit) {
                if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.check_action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissAction, enabled = enabled) { Text(stringResource(R.string.cancel_back)) } },
    )
}

/** Cuenta y cobro (propuesta 05): cada cuenta de la mesa con su total por cobrar; revisar y confirmar el cobro. */
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
    state.recording?.let { ResultDialog(it, viewModel) }
    state.moving?.let { MoveDialog(it, state.checks, can.create, viewModel) }
    state.action?.let { ActionDialog(it, viewModel) }
    val paying = state.paying
    if (paying != null) {
        PayReview(paying, state.shifts, state.shiftsFailure, state.shiftsLoading, viewModel)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.check_screen_title, tableNumber), onBack)
        Column(modifier = Modifier.weight(1f).readableWidth()) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.check_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
            state.actionFailure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
            }
            state.notice?.let {
                Text(
                    if (it.arg != null) stringResource(it.text, it.arg) else stringResource(it.text),
                    color = Restia.colors.free,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
            // Las canceladas no tienen nada que cobrar ni hacer: no se muestran.
            val checks = state.checks.filter { it.status != "CANCELLED" }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && checks.isEmpty() -> EmptyState(stringResource(R.string.check_none), body = null)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    items(checks, key = { it.id }) { check ->
                        CheckSection(check, state.payments[check.id].orEmpty(), can, state.busy, showTitle = checks.size > 1, viewModel = viewModel)
                    }
                }
            }
        }
    }
}
