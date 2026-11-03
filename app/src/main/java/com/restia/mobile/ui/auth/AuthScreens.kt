package com.restia.mobile.ui.auth

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.data.auth.SessionNotice

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            // Mantiene el contenido sobre el teclado; con pantallas chicas se puede desplazar.
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
fun LoadingScreen() {
    ScreenColumn { CircularProgressIndicator() }
}

@Composable
fun OfflineScreen(onRetry: () -> Unit) {
    ScreenColumn {
        Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.offline_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun failureText(failure: ApiFailure): String = when (failure) {
    ApiFailure.Network -> stringResource(R.string.error_network)
    ApiFailure.Unexpected -> stringResource(R.string.error_unexpected)
    is ApiFailure.Server -> when {
        failure.status == 429 && failure.retryAfterSeconds != null ->
            stringResource(R.string.error_too_many, failure.retryAfterSeconds)
        failure.status >= 500 -> stringResource(R.string.error_server)
        else -> failure.message ?: stringResource(R.string.error_unexpected)
    }
}

@Composable
private fun Messages(form: FormState, notice: SessionNotice?) {
    notice?.let {
        val text = when (it) {
            SessionNotice.SESSION_EXPIRED -> R.string.notice_session_expired
            SessionNotice.DEVICE_BLOCKED -> R.string.notice_device_blocked
        }
        Text(stringResource(text), color = MaterialTheme.colorScheme.error)
    }
    form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
fun EnrollScreen(form: FormState, notice: SessionNotice?, onEnroll: (String, String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL.take(80)) }
    ScreenColumn {
        Text(stringResource(R.string.enroll_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.enroll_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.take(32) },
            label = { Text(stringResource(R.string.field_code)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text(stringResource(R.string.field_device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Messages(form, notice)
        Button(
            onClick = { onEnroll(code, name) },
            enabled = !form.busy && code.trim().length >= 8 && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_enroll)) }
    }
}

@Composable
fun LoginScreen(form: FormState, notice: SessionNotice?, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Sin rememberSaveable: la contraseña no debe sobrevivir en el estado guardado.
    var password by remember { mutableStateOf("") }
    ScreenColumn {
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(254) },
            label = { Text(stringResource(R.string.field_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(1024) },
            label = { Text(stringResource(R.string.field_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Messages(form, notice)
        Button(
            onClick = { onLogin(email, password) },
            enabled = !form.busy && email.contains('@') && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_login)) }
    }
}
