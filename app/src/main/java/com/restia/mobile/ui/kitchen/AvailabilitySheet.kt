package com.restia.mobile.ui.kitchen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.ui.orders.availabilityFailureText
import com.restia.mobile.ui.orders.matchesSearch
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator

/** Agotados (cocina): busca un platillo y apágalo o préndelo con su interruptor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvailabilitySheet(viewModel: AvailabilityViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.load() }
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.availability_sheet_title), style = MaterialTheme.typography.headlineSmall, color = Restia.colors.heading)
            Text(
                if (state.soldOut == 0) stringResource(R.string.availability_sheet_none)
                else pluralStringResource(R.plurals.availability_sheet_count, state.soldOut, state.soldOut),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                placeholder = { Text(stringResource(R.string.order_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.stale) Text(stringResource(R.string.order_menu_stale), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val problem = state.actionFailure?.let { (name, failure) ->
                stringResource(R.string.availability_failed, name) + " " +
                    (availabilityFailureText(failure)?.let { stringResource(it) } ?: (failure as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected))
            } ?: state.failure?.let { failure ->
                availabilityFailureText(failure)?.let { stringResource(it) } ?: stringResource(R.string.error_unexpected)
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        when {
            !state.loaded && state.failure == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 32.dp),
            ) {
                items(state.products.filter { matchesSearch(it, query) }, key = { it.id }) { product ->
                    val available = product.availability == "AVAILABLE"
                    val saving = product.id in state.saving
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(available, enabled = !saving, role = Role.Switch) { viewModel.toggle(product) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(product.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                stringResource(if (available) R.string.availability_on else R.string.order_sold_out),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = available, onCheckedChange = null, enabled = !saving)
                    }
                    Separator()
                }
            }
        }
    }
}
