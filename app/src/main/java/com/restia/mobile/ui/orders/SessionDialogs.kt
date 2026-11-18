package com.restia.mobile.ui.orders

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure

/** Texto de una falla al juntar, separar o anular. */
@StringRes
fun sessionFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.code == "TABLE_NOT_AVAILABLE" || failure.code == "TABLE_ALREADY_ASSIGNED" -> R.string.join_taken
    failure.code == "SESSION_LAST_TABLE" -> R.string.release_last
    failure.code == "SESSION_HAS_ACTIVITY" -> R.string.cancel_session_activity
    failure.code == "SESSION_NOT_ACTIVE" -> R.string.guests_inactive
    failure.code == "VERSION_CONFLICT" -> R.string.close_conflict
    failure.status == 403 -> R.string.error_action_forbidden
    else -> null
}

@Composable
private fun FailureLine(failure: ApiFailure?) {
    failure ?: return
    Text(
        sessionFailureText(failure)?.let { stringResource(it) } ?: (failure as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
fun JoinDialog(state: JoinState, tableNumber: Int, viewModel: SessionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissJoin,
        title = { Text(stringResource(R.string.join_title, tableNumber)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.join_body))
                val candidates = state.candidates
                when {
                    candidates == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    candidates.isEmpty() && state.failure == null -> Text(stringResource(R.string.join_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> candidates.sortedBy { it.number }.forEach { table ->
                        val checked = table.id in state.selected
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(checked, enabled = !state.submitting, role = Role.Checkbox) { viewModel.toggleJoin(table.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !state.submitting)
                            Text(
                                listOfNotNull(stringResource(R.string.table_title, table.number), table.zone).joinToString(" · ") +
                                    " · " + pluralStringResource(R.plurals.join_seats, table.capacity, table.capacity),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                FailureLine(state.failure)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmJoin, enabled = state.canConfirm) {
                Text(stringResource(R.string.join_confirm, state.selected.size))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissJoin, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
fun ReleaseDialog(state: ReleaseState, seated: List<com.restia.mobile.core.network.SessionTableDto>, viewModel: SessionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissRelease,
        title = { Text(stringResource(R.string.release_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.release_body))
                seated.forEach { table ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(state.tableId == table.tableId, enabled = !state.submitting, role = Role.RadioButton) { viewModel.selectRelease(table.tableId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.tableId == table.tableId, onClick = null, enabled = !state.submitting)
                        Text(stringResource(R.string.table_title, table.number), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .toggleable(state.requiresCleaning, enabled = !state.submitting, role = Role.Switch) { viewModel.setReleaseCleaning(it) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.release_cleaning), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.requiresCleaning, onCheckedChange = null, enabled = !state.submitting)
                }
                FailureLine(state.failure)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmRelease, enabled = state.canConfirm) { Text(stringResource(R.string.release_confirm)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissRelease, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
fun CancelSessionDialog(state: CancelSessionState, tableNumber: Int, viewModel: SessionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissCancel,
        title = { Text(stringResource(R.string.cancel_session_title, tableNumber)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cancel_session_body))
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = viewModel::setCancelReason,
                    label = { Text(stringResource(R.string.cancel_reason)) },
                    supportingText = { Text(stringResource(R.string.cancel_reason_hint, REASON_MIN)) },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                FailureLine(state.failure)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmCancel, enabled = state.canConfirm) { Text(stringResource(R.string.cancel_session_confirm)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissCancel, enabled = !state.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}
