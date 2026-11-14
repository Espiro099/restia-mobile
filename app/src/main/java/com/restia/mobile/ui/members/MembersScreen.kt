package com.restia.mobile.ui.members

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.MemberDto
import com.restia.mobile.ui.reservations.fieldMessage
import com.restia.mobile.ui.tables.roleLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EXPIRY = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es-MX"))

@Composable
fun roleName(role: String): String = roleLabel(role)?.let { stringResource(it) } ?: role

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 429 && failure.retryAfterSeconds != null -> {
        val seconds = failure.retryAfterSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.error_too_many, seconds, seconds)
    }
    failure is ApiFailure.Server && failure.code == "VERSION_CONFLICT" -> stringResource(R.string.staff_conflict)
    failure is ApiFailure.Server && failure.status == 403 -> failure.message ?: stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server && failure.status == 422 -> fieldMessage(failure) ?: failure.message ?: stringResource(R.string.error_unexpected)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Negro sobre blanco siempre, con margen: así lo lee cualquier cámara aunque la app esté en modo oscuro. */
private fun qrCode(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun RoleChoice(roles: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column {
        roles.forEach { role ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = role == selected, enabled = enabled, role = Role.RadioButton) { onSelect(role) },
            ) {
                RadioButton(selected = role == selected, onClick = null, enabled = enabled)
                Text(roleName(role), modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
            }
        }
    }
}

@Composable
private fun AddDialog(form: AddMemberForm, roles: List<String>, viewModel: MembersViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissAdd,
        title = { Text(stringResource(R.string.staff_add_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.editAdd { it.copy(name = v.take(120)) } },
                    label = { Text(stringResource(R.string.staff_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.email,
                    onValueChange = { v -> viewModel.editAdd { it.copy(email = v.take(254)) } },
                    label = { Text(stringResource(R.string.staff_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.staff_role), style = MaterialTheme.typography.labelLarge)
                RoleChoice(roles, form.role, !form.submitting) { r -> viewModel.editAdd { it.copy(role = r) } }
                Text(stringResource(R.string.staff_add_hint), style = MaterialTheme.typography.bodySmall)
                form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submitAdd, enabled = form.canSubmit) {
                if (form.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.staff_add_confirm))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissAdd, enabled = !form.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LinkDialog(link: SetupLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = link.url
    AlertDialog(
        // Solo se cierra con el botón: el enlace no se vuelve a mostrar.
        onDismissRequest = {},
        title = { Text(if (url != null) stringResource(R.string.staff_link_title, link.name) else link.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (url == null) {
                    Text(stringResource(R.string.staff_existing_account, link.name))
                    return@Column
                }
                Text(stringResource(R.string.staff_link_body))
                val qr = remember(url) { qrCode(url) }
                Image(qr, contentDescription = null, modifier = Modifier.size(220.dp).background(Color.White))
                link.expiresAt?.let { iso ->
                    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(EXPIRY) }.getOrNull()?.let {
                        Text(stringResource(R.string.staff_link_expires, it), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val message = stringResource(R.string.staff_link_message, link.name, url)
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.staff_link_share)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.staff_done)) } },
    )
}

@Composable
private fun MemberDialog(action: MemberAction, state: MembersUiState, viewModel: MembersViewModel, canResendLink: Boolean) {
    val member = action.member
    val roles = manageableRoles(viewModel.actorRole)
    val manageable = canManage(viewModel.actorRole, viewModel.actorUserId, member)
    val busy = action.submitting || state.requestingLink
    AlertDialog(
        onDismissRequest = viewModel::dismissSelected,
        title = { Text(member.user.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(member.user.email, style = MaterialTheme.typography.bodyMedium)
                Text(roleName(member.role) + statusSuffix(member), style = MaterialTheme.typography.bodySmall)
                if (!manageable) Text(stringResource(R.string.staff_no_actions), style = MaterialTheme.typography.bodySmall)
                if (manageable && !action.revoking) {
                    HorizontalDivider()
                    Text(stringResource(R.string.staff_change_role), style = MaterialTheme.typography.labelLarge)
                    RoleChoice(roles, action.role, !busy, viewModel::chooseRole)
                    if (action.role != member.role) {
                        Button(onClick = viewModel::saveRole, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.staff_save_role)) }
                    }
                }
                if (manageable && action.revoking) {
                    HorizontalDivider()
                    Text(stringResource(R.string.staff_revoke_body))
                    OutlinedTextField(
                        value = action.reason,
                        onValueChange = viewModel::setReason,
                        label = { Text(stringResource(R.string.staff_revoke_reason)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = viewModel::confirmRevoke, enabled = !busy && action.reason.trim().length >= 3, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.staff_revoke_confirm))
                    }
                }
                if (canResendLink && member.active && member.user.status == "INVITED" && member.user.id != viewModel.actorUserId) {
                    HorizontalDivider()
                    TextButton(onClick = viewModel::newSetupLink, enabled = !busy) { Text(stringResource(R.string.staff_new_link)) }
                    Text(stringResource(R.string.staff_new_link_hint), style = MaterialTheme.typography.bodySmall)
                    state.linkFailure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                }
                action.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissSelected, enabled = !busy) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (manageable && !action.revoking) {
                TextButton(onClick = viewModel::startRevoke, enabled = !busy) { Text(stringResource(R.string.staff_revoke), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

@Composable
private fun statusSuffix(member: MemberDto): String = when {
    !member.active -> " · " + stringResource(R.string.staff_revoked)
    member.user.status == "INACTIVE" -> " · " + stringResource(R.string.staff_user_inactive)
    member.user.status == "INVITED" -> " · " + stringResource(R.string.staff_invited)
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(viewModel: MembersViewModel, canResendLink: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val roles = manageableRoles(viewModel.actorRole)
    state.adding?.let { AddDialog(it, roles, viewModel) }
    state.selected?.let { MemberDialog(it, state, viewModel, canResendLink) }
    state.link?.let { LinkDialog(it, viewModel::dismissLink) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.staff_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
                actions = { if (roles.isNotEmpty()) TextButton(onClick = viewModel::startAdd) { Text(stringResource(R.string.staff_add)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.staff_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text(stringResource(R.string.action_refresh)) }
                if (state.revokedCount > 0) {
                    TextButton(onClick = viewModel::toggleRevoked) {
                        Text(if (state.showRevoked) stringResource(R.string.staff_hide_revoked) else stringResource(R.string.staff_show_revoked, state.revokedCount))
                    }
                }
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.staff_empty)) }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.visible, key = { it.user.id }) { member ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.select(member) }) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val you = if (member.user.id == viewModel.actorUserId) " " + stringResource(R.string.staff_you) else ""
                                Text(member.user.name + you, style = MaterialTheme.typography.titleMedium)
                                Text(member.user.email, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    roleName(member.role) + statusSuffix(member),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (member.active && member.user.status == "ACTIVE") Color.Unspecified else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
