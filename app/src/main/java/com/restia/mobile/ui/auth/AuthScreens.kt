package com.restia.mobile.ui.auth

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.data.auth.SessionNotice
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.RestiaLogo

/**
 * Fondo de las pantallas de acceso: crema arriba y, abajo, un arco cálido de terracota a café que hace las veces
 * de la foto del restaurante de la propuesta (sin cargar imágenes pesadas).
 */
@Composable
private fun WarmArch(modifier: Modifier = Modifier) {
    val brand = Restia.colors.brand
    val deep = Restia.colors.brandDeep
    val lamp = Restia.colors.busy
    Canvas(modifier = modifier) {
        val top = size.height * 0.28f
        val path = Path().apply {
            moveTo(0f, top + size.height * 0.18f)
            quadraticTo(size.width / 2f, -size.height * 0.08f, size.width, top + size.height * 0.18f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(brand.copy(alpha = 0.92f), deep), startY = 0f, endY = size.height))
        // Lámparas al fondo: un punto cálido con su halo.
        listOf(0.18f to 0.66f, 0.5f to 0.56f, 0.82f to 0.68f).forEach { (x, y) ->
            val center = Offset(size.width * x, size.height * y)
            val halo = size.width * 0.09f
            drawCircle(Brush.radialGradient(listOf(lamp.copy(alpha = 0.45f), lamp.copy(alpha = 0f)), center, halo), radius = halo, center = center)
            drawCircle(lamp, radius = size.width * 0.012f, center = center)
        }
    }
}

@Composable
private fun AuthLayout(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WarmArch(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp))
        Column(
            // Mantiene el contenido sobre el teclado; con pantallas chicas se puede desplazar.
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 48.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RestiaLogo(height = 52.dp, tagline = true)
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String?) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Restia.colors.heading, textAlign = TextAlign.Center)
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    @DrawableRes icon: Int,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    secret: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painterResource(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = stringResource(if (visible) R.string.password_conceal else R.string.password_reveal),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        keyboardOptions = keyboard,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp)) else Text(text)
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            RestiaLogo(height = 56.dp, tagline = true)
            CircularProgressIndicator()
        }
    }
}

@Composable
fun OfflineScreen(onRetry: () -> Unit) {
    AuthLayout {
        Heading(stringResource(R.string.offline_title), stringResource(R.string.offline_body))
        PrimaryButton(stringResource(R.string.action_retry), enabled = true, busy = false, onClick = onRetry)
    }
}

@Composable
private fun failureText(failure: ApiFailure): String = when (failure) {
    ApiFailure.Network -> stringResource(R.string.error_network)
    ApiFailure.Unexpected -> stringResource(R.string.error_unexpected)
    is ApiFailure.Server -> when {
        failure.status == 429 && failure.retryAfterSeconds != null -> {
            val seconds = failure.retryAfterSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.error_too_many, seconds, seconds)
        }
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
        Text(stringResource(text), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    form.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
}

@Composable
fun EnrollScreen(form: FormState, notice: SessionNotice?, onEnroll: (String, String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL.take(80)) }
    AuthLayout {
        Heading(stringResource(R.string.enroll_title), stringResource(R.string.enroll_body))
        Field(code, { code = it.take(32) }, stringResource(R.string.field_code), R.drawable.ic_lock)
        Field(name, { name = it.take(80) }, stringResource(R.string.field_device_name), R.drawable.ic_place)
        Messages(form, notice)
        PrimaryButton(
            stringResource(R.string.action_enroll),
            enabled = !form.busy && code.trim().length >= 8 && name.isNotBlank(),
            busy = form.busy,
        ) { onEnroll(code, name) }
    }
}

@Composable
fun LoginScreen(form: FormState, notice: SessionNotice?, onLogin: (String, String) -> Unit, onForgot: (email: String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Sin rememberSaveable: la contraseña no debe sobrevivir en el estado guardado.
    var password by remember { mutableStateOf("") }
    AuthLayout {
        Heading(stringResource(R.string.login_welcome), stringResource(R.string.login_subtitle))
        Field(
            email,
            { email = it.take(254) },
            stringResource(R.string.field_email),
            R.drawable.ic_person,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Field(
            password,
            { password = it.take(1024) },
            stringResource(R.string.field_password),
            R.drawable.ic_lock,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            secret = true,
        )
        Messages(form, notice)
        PrimaryButton(
            stringResource(R.string.action_login),
            enabled = !form.busy && email.contains('@') && password.isNotEmpty(),
            busy = form.busy,
        ) { onLogin(email, password) }
        TextButton(onClick = { onForgot(email) }, enabled = !form.busy) { Text(stringResource(R.string.password_forgot)) }
    }
}
