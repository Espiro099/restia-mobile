package com.restia.mobile.ui.auth

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.data.auth.SessionNotice
import com.restia.mobile.ui.theme.PrimaryButton
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.RestiaLogo
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.SecondaryButton

/**
 * Fondo del acceso (propuesta 01 y 11): la foto del restaurante abajo y, encima, la crema con su borde curvo.
 * La crema cubre el 69 % de la pantalla, como en el diseño (590 de 852).
 */
@Composable
private fun PhotoBackground() {
    val cream = MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxSize().background(cream)) {
        Image(
            painterResource(R.drawable.restia_photo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height * 0.69f
            val dip = size.height * 0.053f
            // Mismo trazo que el marco de la propuesta: baja recto y cierra con una curva suave hacia abajo.
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, h - dip)
                cubicTo(size.width * 0.667f, h, size.width * 0.333f, h, 0f, h - dip)
                close()
            }
            drawPath(path, cream)
        }
    }
}

/** Acceso: fondo de foto y el contenido sobre la crema, desplazable con el teclado abierto. */
@Composable
private fun AuthLayout(centered: Boolean, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val photo = maxHeight * 0.33f
        PhotoBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(top = if (centered) 52.dp else 24.dp, bottom = photo),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                content = content,
            )
        }
    }
}

/** Título y explicación, alineados a la izquierda (acceso y alta). */
@Composable
private fun Heading(title: String, subtitle: String?) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = Restia.colors.heading,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Campo con su etiqueta arriba, sobre papel y con borde de arena (propuesta 11). */
@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    secret: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
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
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        textStyle = MaterialTheme.typography.bodyMedium,
        // La etiqueta va visible arriba; el lector de pantalla la anuncia junto con el campo.
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            RestiaLogo(height = 78.dp, tagline = true)
            CircularProgressIndicator()
        }
    }
}

/** Título centrado de la bienvenida y de "sin conexión". */
@Composable
private fun CenteredHeading(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 31.sp),
        color = Restia.colors.heading,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        body,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp, bottom = 19.dp),
    )
}

@Composable
fun OfflineScreen(onRetry: () -> Unit) {
    AuthLayout(centered = true) {
        RestiaLogo(height = 78.dp, tagline = true)
        Spacer(Modifier.height(29.dp))
        CenteredHeading(stringResource(R.string.offline_title), stringResource(R.string.offline_body))
        PrimaryButton(stringResource(R.string.action_retry), onRetry)
    }
}

@Composable
private fun failureText(failure: ApiFailure, google: Boolean = false): String = when (failure) {
    ApiFailure.Network -> stringResource(R.string.error_network)
    ApiFailure.Unexpected -> stringResource(R.string.error_unexpected)
    is ApiFailure.Server -> when {
        // Con Google no hay contraseña: lo que falla es que esa cuenta no sea del personal.
        google && failure.code == "AUTHENTICATION_INVALID" -> stringResource(R.string.google_not_staff)
        google && failure.status == 404 -> stringResource(R.string.google_failed)
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
        Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    form.problem?.let {
        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    form.failure?.let {
        Text(failureText(it, form.google), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Bienvenida (propuesta 01): la marca, el saludo y el paso a iniciar sesión. */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    AuthLayout(centered = true) {
        RestiaLogo(height = 78.dp, tagline = true)
        Spacer(Modifier.height(29.dp))
        CenteredHeading(stringResource(R.string.login_welcome), stringResource(R.string.welcome_body))
        PrimaryButton(stringResource(R.string.welcome_action), onContinue)
        Text(
            stringResource(R.string.welcome_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
fun EnrollScreen(form: FormState, notice: SessionNotice?, onEnroll: (String, String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL.take(80)) }
    AuthLayout(centered = false) {
        RestiaLogo(height = 54.dp)
        Heading(stringResource(R.string.enroll_title), stringResource(R.string.enroll_body))
        Field(code, { code = it.take(32) }, stringResource(R.string.field_code))
        Field(name, { name = it.take(80) }, stringResource(R.string.field_device_name))
        Messages(form, notice)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            stringResource(R.string.action_enroll),
            onClick = { onEnroll(code, name) },
            enabled = code.trim().length >= 8 && name.isNotBlank(),
            busy = form.busy,
        )
    }
}

/** Iniciar sesión (propuesta 11). */
@Composable
fun LoginScreen(
    form: FormState,
    notice: SessionNotice?,
    onLogin: (String, String) -> Unit,
    onForgot: (email: String) -> Unit,
    googleEnabled: Boolean = false,
    onGoogle: () -> Unit = {},
) {
    var email by rememberSaveable { mutableStateOf("") }
    // Sin rememberSaveable: la contraseña no debe sobrevivir en el estado guardado.
    var password by remember { mutableStateOf("") }
    AuthLayout(centered = false) {
        RestiaLogo(height = 54.dp)
        Heading(stringResource(R.string.login_title), stringResource(R.string.login_hint))
        Field(
            email,
            { email = it.take(254) },
            stringResource(R.string.field_email),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Field(
            password,
            { password = it.take(1024) },
            stringResource(R.string.field_password),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            secret = true,
        )
        Messages(form, notice)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            stringResource(R.string.action_login),
            onClick = { onLogin(email, password) },
            enabled = email.contains('@') && password.isNotEmpty(),
            busy = form.busy && !form.google,
        )
        // Solo si la API tiene GOOGLE_CLIENT_ID; entra el personal ya dado de alta con ese correo.
        if (googleEnabled) {
            Text(
                stringResource(R.string.login_or),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
            SecondaryButton(
                if (form.busy && form.google) stringResource(R.string.google_signing_in) else stringResource(R.string.action_google),
                onClick = onGoogle,
                enabled = !form.busy,
            )
        }
        Text(
            stringResource(R.string.login_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = { onForgot(email) }, enabled = !form.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.password_forgot))
        }
    }
}
