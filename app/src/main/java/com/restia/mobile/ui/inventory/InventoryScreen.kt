package com.restia.mobile.ui.inventory

import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.IngredientDto
import com.restia.mobile.data.inventory.isLow
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenHeader
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import kotlinx.coroutines.delay

/** Unidad base como se lee en pantalla. */
@StringRes
fun unitLabel(baseUnit: String): Int = when (baseUnit) {
    "G" -> R.string.unit_g
    "KG" -> R.string.unit_kg
    "ML" -> R.string.unit_ml
    "L" -> R.string.unit_l
    else -> R.string.unit_piece
}

@StringRes
fun actionLabel(action: InventoryAction): Int = when (action) {
    InventoryAction.WASTE -> R.string.inventory_waste
    InventoryAction.RECEIVE -> R.string.inventory_receive
    InventoryAction.COUNT -> R.string.inventory_count
    InventoryAction.BLOCK -> R.string.inventory_block
}

@StringRes
fun inventoryFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.status == 403 -> R.string.error_action_forbidden
    failure.code == "FORBIDDEN" -> R.string.error_action_forbidden
    else -> null
}

@Composable
private fun failureMessage(failure: ApiFailure): String =
    inventoryFailureText(failure)?.let { stringResource(it) } ?: (failure as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected)

/** Inventario (cocina): stock contra el mínimo y, al tocar un insumo, merma, entrada, conteo o bloqueo. */
@Composable
fun InventoryScreen(viewModel: InventoryViewModel, branchName: String, permissions: InventoryPermissions) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(state.done) {
        if (state.done != null) {
            delay(5_000)
            viewModel.clearDone()
        }
    }
    state.selected?.let { ingredient ->
        val action = state.action
        if (action == null) ActionSheet(ingredient, permissions.actions(ingredient), viewModel) else ActionDialog(ingredient, action, state, viewModel)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.inventory_title)) {
            TextButton(onClick = viewModel::load, enabled = !state.refreshing) { Text(stringResource(R.string.action_refresh)) }
        }
        Column(modifier = Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(branchName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.inventory_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.filter == InventoryFilter.ALL, { viewModel.setFilter(InventoryFilter.ALL) }, { Text(stringResource(R.string.inventory_filter_all)) })
                FilterChip(state.filter == InventoryFilter.LOW, { viewModel.setFilter(InventoryFilter.LOW) }, { Text(stringResource(R.string.inventory_filter_low, state.low)) })
                FilterChip(state.filter == InventoryFilter.BLOCKED, { viewModel.setFilter(InventoryFilter.BLOCKED) }, { Text(stringResource(R.string.inventory_filter_blocked, state.blocked)) })
            }
            state.done?.let { (action, name) ->
                val text = when (action) {
                    InventoryAction.WASTE -> R.string.inventory_done_waste
                    InventoryAction.RECEIVE -> R.string.inventory_done_receive
                    InventoryAction.COUNT -> R.string.inventory_done_count
                    InventoryAction.BLOCK -> R.string.inventory_done_block
                }
                Text(stringResource(text, name), style = MaterialTheme.typography.bodySmall, color = Restia.colors.free)
            }
            state.failure?.let { Text(failureMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        when {
            !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.loaded && state.visible.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(
                    stringResource(if (state.ingredients.isEmpty()) R.string.inventory_empty else R.string.inventory_none_match),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 32.dp)) {
                items(state.visible, key = { it.id }) { ingredient ->
                    IngredientRow(ingredient, enabled = permissions.actions(ingredient).isNotEmpty()) { viewModel.select(ingredient) }
                    Separator()
                }
            }
        }
    }
}

@Composable
private fun IngredientRow(ingredient: IngredientDto, enabled: Boolean, onClick: () -> Unit) {
    val unit = stringResource(unitLabel(ingredient.baseUnit))
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ingredient.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                stringResource(R.string.inventory_stock_line, ingredient.stock, unit, ingredient.minStock, unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            ingredient.foodSafetyBlock != null -> StatusPill(stringResource(R.string.inventory_pill_blocked), MaterialTheme.colorScheme.error)
            ingredient.isLow() -> StatusPill(stringResource(R.string.inventory_pill_low), Restia.colors.tables.waiting.content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(ingredient: IngredientDto, actions: List<InventoryAction>, viewModel: InventoryViewModel) {
    ModalBottomSheet(
        onDismissRequest = viewModel::cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp)) {
            Text(ingredient.name, style = MaterialTheme.typography.headlineSmall, color = Restia.colors.heading)
            ingredient.foodSafetyBlock?.let {
                Text(stringResource(R.string.inventory_blocked_reason, it.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            actions.forEach { action ->
                Text(
                    stringResource(actionLabel(action)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { viewModel.start(action) }.padding(vertical = 14.dp),
                )
                Separator()
            }
        }
    }
}

@Composable
private fun ActionDialog(ingredient: IngredientDto, action: InventoryAction, state: InventoryUiState, viewModel: InventoryViewModel) {
    val form = state.form
    val unit = stringResource(unitLabel(ingredient.baseUnit))
    val problem = formProblem(action, form)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = viewModel::cancel,
        title = { Text(stringResource(actionLabel(action)) + " · " + ingredient.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        when (action) {
                            InventoryAction.WASTE -> R.string.inventory_waste_help
                            InventoryAction.RECEIVE -> R.string.inventory_receive_help
                            InventoryAction.COUNT -> R.string.inventory_count_help
                            InventoryAction.BLOCK -> R.string.inventory_block_help
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action != InventoryAction.BLOCK) {
                    OutlinedTextField(
                        value = form.quantity,
                        onValueChange = { viewModel.edit(form.copy(quantity = it.take(16))) },
                        label = { Text(stringResource(if (action == InventoryAction.COUNT) R.string.inventory_counted else R.string.inventory_quantity, unit)) },
                        singleLine = true,
                        keyboardOptions = decimal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action == InventoryAction.RECEIVE) {
                    OutlinedTextField(
                        value = form.unitCost,
                        onValueChange = { viewModel.edit(form.copy(unitCost = it.take(16))) },
                        label = { Text(stringResource(R.string.inventory_unit_cost, unit)) },
                        singleLine = true,
                        keyboardOptions = decimal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.supplier,
                        onValueChange = { viewModel.edit(form.copy(supplier = it.take(120))) },
                        label = { Text(stringResource(R.string.inventory_supplier)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action == InventoryAction.WASTE || action == InventoryAction.BLOCK) {
                    OutlinedTextField(
                        value = form.reason,
                        onValueChange = { viewModel.edit(form.copy(reason = it.take(500))) },
                        label = { Text(stringResource(R.string.inventory_reason)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.actionFailure?.let { Text(failureMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = problem == null && !state.saving) {
                Text(stringResource(if (state.saving) R.string.inventory_saving else R.string.inventory_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::cancel, enabled = !state.saving) { Text(stringResource(R.string.action_cancel)) } },
    )
}

