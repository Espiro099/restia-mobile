package com.restia.mobile.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.ui.reservations.fieldMessage

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 429 && failure.retryAfterSeconds != null -> {
        val seconds = failure.retryAfterSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.error_too_many, seconds, seconds)
    }
    failure is ApiFailure.Server && failure.status == 422 -> fieldMessage(failure) ?: failure.message ?: stringResource(R.string.error_unexpected)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Menú de la barra superior: personal (si puede verlo), cambiar contraseña y cerrar sesión. */
@Composable
fun AccountMenu(showStaff: Boolean, logoutEnabled: Boolean, onStaff: () -> Unit, onChangePassword: () -> Unit, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.account_menu)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showStaff) {
                DropdownMenuItem(text = { Text(stringResource(R.string.staff_title)) }, onClick = { open = false; onStaff() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.password_change_title)) }, onClick = { open = false; onChangePassword() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_logout)) },
                enabled = logoutEnabled,
                onClick = { open = false; onLogout() },
            )
        }
    }
}

@Composable
private fun PasswordField(value: String, label: Int, visible: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(viewModel: ChangePasswordViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    // Mostrar las contraseñas es una preferencia de esta pantalla; no se guarda.
    var visible by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.password_change_title)) },
                navigationIcon = { TextButton(onClick = onBack, enabled = !state.submitting) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.done) {
                Text(stringResource(R.string.password_changed))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.staff_done)) }
                return@Column
            }
            val enabled = !state.submitting
            PasswordField(state.current, R.string.password_current, visible, enabled) { v -> viewModel.edit { it.copy(current = v) } }
            PasswordField(state.new, R.string.password_new, visible, enabled) { v -> viewModel.edit { it.copy(new = v) } }
            Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
            PasswordField(state.confirm, R.string.password_confirm, visible, enabled) { v -> viewModel.edit { it.copy(confirm = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = visible, onCheckedChange = { visible = it })
                Text(stringResource(R.string.password_show))
            }
            // Solo se avisa cuando ya escribió algo en el campo correspondiente.
            val problem = when (state.problem) {
                PasswordProblem.TOO_SHORT -> R.string.password_too_short.takeIf { state.new.isNotEmpty() && state.confirm.isNotEmpty() }
                PasswordProblem.MISMATCH -> R.string.password_mismatch.takeIf { state.confirm.length >= state.new.length }
                PasswordProblem.SAME_AS_CURRENT -> R.string.password_same
                null -> null
            }
            problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.password_save))
            }
        }
    }
}

@Composable
fun ForgotPasswordDialog(state: ForgotPasswordState, onEmail: (String) -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_forgot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sent) {
                    Text(stringResource(R.string.password_forgot_sent))
                } else {
                    Text(stringResource(R.string.password_forgot_body))
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmail,
                        label = { Text(stringResource(R.string.field_email)) },
                        singleLine = true,
                        enabled = !state.submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (state.sent) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.staff_done)) }
            } else {
                TextButton(onClick = onSubmit, enabled = state.canSubmit) {
                    if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.password_forgot_send))
                }
            }
        },
        dismissButton = { if (!state.sent) TextButton(onClick = onDismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}
