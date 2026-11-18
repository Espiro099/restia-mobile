package com.restia.mobile.ui.devices

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.DeviceDto
import com.restia.mobile.data.devices.DeviceClosing
import com.restia.mobile.data.devices.DevicesRepository
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.GroupLabel
import com.restia.mobile.ui.theme.PrimaryButton
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import com.restia.mobile.ui.theme.readableWidth
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale.forLanguageTag("es-MX")

private fun local(iso: String?) = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() }

/** "hoy 14:05", "ayer 09:30" o "12 nov 18:00", en la zona del teléfono. */
@Composable
private fun whenText(iso: String?): String? {
    val at = local(iso) ?: return null
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = LocalDate.now()
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.sessions_today, time)
        today.minusDays(1) -> stringResource(R.string.sessions_yesterday, time)
        else -> at.format(DateTimeFormatter.ofPattern("d MMM HH:mm", SPANISH)).replace(".", "")
    }
}

@Composable
private fun devicesFailureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status == 429 -> stringResource(R.string.devices_too_many_codes)
    failure is ApiFailure.Server && failure.code == "BRANCH_INACTIVE" -> stringResource(R.string.devices_branch_inactive)
    failure is ApiFailure.Server && failure.status == 404 -> stringResource(R.string.devices_not_found)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun StatusOf(device: DeviceDto) {
    when (device.status) {
        DevicesRepository.ACTIVE -> StatusPill(stringResource(R.string.devices_status_active), Restia.colors.free)
        DevicesRepository.BLOCKED -> StatusPill(stringResource(R.string.devices_status_blocked), MaterialTheme.colorScheme.error)
        DevicesRepository.RETIRED -> StatusPill(stringResource(R.string.devices_status_retired), Restia.colors.idle)
        else -> StatusPill(device.status, Restia.colors.idle)
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, isThis: Boolean, actions: Boolean, onBlock: () -> Unit, onRetire: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Restia.colors.heading)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusOf(device)
                if (isThis) StatusPill(stringResource(R.string.devices_this), Restia.colors.brand)
            }
            whenText(device.lastSeenAt)?.let {
                Text(stringResource(R.string.devices_last_seen, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            whenText(device.enrolledAt)?.let {
                Text(stringResource(R.string.devices_enrolled, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actions) {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRetire) { Text(stringResource(R.string.devices_retire)) }
                TextButton(onClick = onBlock) { Text(stringResource(R.string.devices_block), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CodeDialog(state: DevicesUiState, onDismiss: () -> Unit) {
    val code = state.code ?: return
    val spoken = code.code.toList().joinToString(" ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.devices_code_body))
                Text(
                    groupCode(code.code),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    color = Restia.colors.heading,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
                )
                local(code.expiresAt)?.let {
                    Text(
                        stringResource(R.string.devices_code_expires, it.format(DateTimeFormatter.ofPattern("HH:mm"))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.staff_done)) } },
    )
}

@Composable
private fun ClosingDialog(closing: ClosingState, onReason: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val block = closing.how == DeviceClosing.BLOCK
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (block) R.string.devices_block_title else R.string.devices_retire_title, closing.device.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (block) R.string.devices_block_body else R.string.devices_retire_body))
                OutlinedTextField(
                    value = closing.reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.devices_reason)) },
                    enabled = !closing.submitting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                closing.failure?.let { Text(devicesFailureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = closing.canSubmit) {
                Text(
                    stringResource(if (block) R.string.devices_block else R.string.devices_retire),
                    color = if (block && closing.canSubmit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !closing.submitting) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@Composable
fun DevicesScreen(viewModel: DevicesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val thisDevice = state.thisDevice
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel) { viewModel.load() }
    CodeDialog(state, viewModel::dismissCode)
    state.closing?.let { ClosingDialog(it, viewModel::setReason, viewModel::confirmClosing, viewModel::dismissClosing) }
    val active = state.devices.filter { it.status == DevicesRepository.ACTIVE }
    val closed = state.devices.filterNot { it.status == DevicesRepository.ACTIVE }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.devices_title), onBack = onBack)
        Column(modifier = Modifier.weight(1f).readableWidth()) {
            Column(modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.devices_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (viewModel.permissions.enroll) {
                    PrimaryButton(stringResource(R.string.devices_new_code), onClick = viewModel::generateCode, busy = state.generating)
                    state.codeFailure?.let { Text(devicesFailureText(it), color = MaterialTheme.colorScheme.error) }
                }
                state.closed?.let {
                    Text(
                        stringResource(if (it == DeviceClosing.BLOCK) R.string.devices_blocked_done else R.string.devices_retired_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = Restia.colors.free,
                    )
                }
                state.failure?.let { Text(devicesFailureText(it), color = MaterialTheme.colorScheme.error) }
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.devices.isEmpty() -> EmptyState(stringResource(R.string.devices_empty), null)
                else -> LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp)) {
                    if (active.isNotEmpty()) item(key = "activos") { GroupLabel(stringResource(R.string.devices_group_active, active.size)) }
                    items(active, key = { it.id }) { device ->
                        DeviceRow(
                            device,
                            isThis = device.id.equals(thisDevice, ignoreCase = true),
                            actions = viewModel.permissions.manage && canClose(device, thisDevice) && state.closing == null,
                            onBlock = { viewModel.startClosing(device, DeviceClosing.BLOCK) },
                            onRetire = { viewModel.startClosing(device, DeviceClosing.RETIRE) },
                        )
                        Separator()
                    }
                    if (closed.isNotEmpty()) item(key = "cerrados") { GroupLabel(stringResource(R.string.devices_group_closed, closed.size), Modifier.padding(top = 16.dp)) }
                    items(closed, key = { it.id }) { device ->
                        DeviceRow(device, isThis = false, actions = false, onBlock = {}, onRetire = {})
                        Separator()
                    }
                }
            }
        }
    }
}
