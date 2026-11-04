package com.restia.mobile.ui.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.data.orders.CartLine
import com.restia.mobile.data.orders.MAX_QUANTITY
import com.restia.mobile.data.orders.invalidModifiers
import java.math.BigDecimal
import java.math.RoundingMode

fun moneyText(amount: BigDecimal, currency: String): String =
    "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency

private fun moneyText(money: MoneyDto): String =
    runCatching { moneyText(BigDecimal(money.amount), money.currency) }.getOrDefault(money.amount)

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun ProductRow(product: MenuProductDto, onClick: () -> Unit) {
    val available = product.availability == "AVAILABLE"
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(product.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (available) moneyText(product.price) else stringResource(R.string.order_sold_out),
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun EditorDialog(viewModel: OrderViewModel, editor: ProductEditor) {
    val modifiers = viewModel.modifiersOf(editor.product)
    val invalid = invalidModifiers(modifiers, editor.selected)
    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = { Text(editor.product.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.setQuantity(editor.quantity - 1) }, enabled = editor.quantity > 1) { Text("−") }
                    Text(editor.quantity.toString(), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { viewModel.setQuantity(editor.quantity + 1) }, enabled = editor.quantity < MAX_QUANTITY) { Text("+") }
                }
                modifiers.forEach { modifier ->
                    Text(modifier.name, style = MaterialTheme.typography.titleSmall)
                    if (editor.showInvalid && modifier in invalid) {
                        Text(
                            stringResource(R.string.order_modifier_range, modifier.minSelections, modifier.maxSelections),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    modifier.options.forEach { option ->
                        val selected = option.id in editor.selected[modifier.id].orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleOption(modifier.id, option.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = { viewModel.toggleOption(modifier.id, option.id) })
                            Text(option.name + "  " + moneyText(option.priceDelta))
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.order_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::addEditorToCart) { Text(stringResource(R.string.order_add)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CartLineRow(line: CartLine, locked: Boolean, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.quantity.toString() + " × " + line.product.name)
            line.options.forEach { Text(it.option.name, style = MaterialTheme.typography.bodySmall) }
            line.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (!locked) TextButton(onClick = onRemove) { Text(stringResource(R.string.order_remove)) }
    }
}

@Composable
private fun ReviewDialog(viewModel: OrderViewModel, state: OrderUiState) {
    val currency = state.cart.firstOrNull()?.product?.price?.currency.orEmpty()
    val estimate = runCatching { state.cart.fold(BigDecimal.ZERO) { sum, l -> sum + l.estimate } }.getOrNull()
    AlertDialog(
        onDismissRequest = { viewModel.setReviewing(false) },
        title = { Text(stringResource(R.string.order_review)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.cart.forEach { line ->
                    CartLineRow(line, state.uncertain || state.sending || state.readOnly) { viewModel.removeLine(line) }
                }
                HorizontalDivider()
                estimate?.let { Text(stringResource(R.string.order_estimate, moneyText(it, currency))) }
                if (state.readOnly) Text(stringResource(R.string.order_read_only), color = MaterialTheme.colorScheme.error)
                if (state.uncertain) {
                    Text(stringResource(R.string.order_uncertain), color = MaterialTheme.colorScheme.error)
                } else {
                    state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::send, enabled = !state.sending) {
                Text(stringResource(if (state.uncertain) R.string.order_retry else R.string.order_send))
            }
        },
        dismissButton = {
            if (state.uncertain) {
                TextButton(onClick = viewModel::discard, enabled = !state.sending) { Text(stringResource(R.string.order_discard)) }
            } else {
                TextButton(onClick = { viewModel.setReviewing(false) }, enabled = !state.sending) { Text(stringResource(R.string.order_keep_adding)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(viewModel: OrderViewModel, tableNumber: Int, onBack: () -> Unit, onSent: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(state.sent) {
        if (state.sent) {
            viewModel.consumeSent()
            onSent()
        }
    }
    state.editor?.let { EditorDialog(viewModel, it) }
    if (state.reviewing) ReviewDialog(viewModel, state)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.order_title, tableNumber)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.order_back)) } },
            )
        },
        bottomBar = {
            if (state.cart.isNotEmpty()) {
                Button(
                    onClick = { viewModel.setReviewing(true) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text(stringResource(R.string.order_view_cart, state.cart.sumOf { it.quantity })) }
            }
        },
    ) { padding ->
        val menu = state.menu
        when {
            menu != null -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.menuStale || state.readOnly) {
                    item(key = "avisos") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (state.menuStale) Text(stringResource(R.string.order_menu_stale), color = MaterialTheme.colorScheme.error)
                            if (state.readOnly) Text(stringResource(R.string.order_read_only), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                menu.products.groupBy { it.category }.forEach { (category, products) ->
                    item(key = "c-$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        )
                    }
                    items(products.sortedBy { it.displayOrder }, key = { it.id }) { product ->
                        ProductRow(product) { viewModel.selectProduct(product) }
                    }
                }
            }
            state.menuFailure != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.order_menu_failed) + " " + failureText(state.menuFailure!!))
                Button(onClick = viewModel::loadMenu) { Text(stringResource(R.string.action_retry)) }
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
