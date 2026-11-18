package com.restia.mobile.ui.account

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.AuthSessionDto
import com.restia.mobile.data.account.SessionsRepository
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<AuthSessionDto> = emptyList(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    /** Sesión a cerrar, en espera de confirmar. */
    val confirming: AuthSessionDto? = null,
    val revoking: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val revoked: Boolean = false,
)

/** Mis sesiones: ver dónde está abierta la cuenta y cerrar las demás. La actual se cierra con "Cerrar sesión". */
class SessionsViewModel(private val repository: SessionsRepository) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = when (val r = repository.active()) {
                is ApiResult.Success -> _state.value.copy(sessions = r.value, loaded = true, failure = null)
                is ApiResult.Failure -> _state.value.copy(failure = r.failure)
            }
        }
    }

    fun ask(session: AuthSessionDto) {
        if (session.current || _state.value.revoking) return
        _state.value = _state.value.copy(confirming = session, actionFailure = null, revoked = false)
    }

    fun dismiss() {
        if (!_state.value.revoking) _state.value = _state.value.copy(confirming = null)
    }

    fun revoke() {
        val target = _state.value.confirming ?: return
        if (_state.value.revoking) return
        _state.value = _state.value.copy(revoking = true, actionFailure = null)
        viewModelScope.launch {
            when (val r = repository.revoke(target.id)) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    sessions = _state.value.sessions.filterNot { it.id == target.id },
                    confirming = null,
                    revoking = false,
                    revoked = true,
                )
                is ApiResult.Failure -> _state.value = _state.value.copy(revoking = false, actionFailure = r.failure)
            }
        }
    }

    class Factory(private val repository: SessionsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionsViewModel(repository) as T
    }
}

private val SPANISH = Locale.forLanguageTag("es-MX")

/** "hoy 14:05", "ayer 09:30" o "12 nov 18:00", en la zona del dispositivo. */
@Composable
private fun whenText(iso: String): String {
    val at = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() ?: return iso
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = LocalDate.now()
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.sessions_today, time)
        today.minusDays(1) -> stringResource(R.string.sessions_yesterday, time)
        else -> at.format(DateTimeFormatter.ofPattern("d MMM HH:mm", SPANISH)).replace(".", "")
    }
}

@Composable
private fun sessionsFailureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun SessionRow(session: AuthSessionDto, busy: Boolean, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (session.clientType == "WEB") R.string.sessions_web else R.string.sessions_android),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Restia.colors.heading,
                )
                if (session.current) StatusPill(stringResource(R.string.sessions_current), Restia.colors.free)
            }
            Text(
                stringResource(R.string.sessions_last_activity, whenText(session.lastActivityAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.sessions_expires, whenText(session.absoluteExpiresAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!session.current) TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.sessions_close)) }
    }
}

@Composable
fun SessionsScreen(viewModel: SessionsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel) { viewModel.load() }
    state.confirming?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.sessions_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (target.clientType == "WEB") R.string.sessions_confirm_web else R.string.sessions_confirm_android))
                    state.actionFailure?.let { Text(sessionsFailureText(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::revoke, enabled = !state.revoking) { Text(stringResource(R.string.sessions_close)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.revoking) { Text(stringResource(R.string.cancel_back)) } },
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.sessions_title), onBack = onBack)
        Column(modifier = Modifier.weight(1f).readableWidth()) {
            Text(
                stringResource(R.string.sessions_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
            )
            if (state.revoked) {
                Text(
                    stringResource(R.string.sessions_closed_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = Restia.colors.free,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
            state.failure?.let {
                Text(sessionsFailureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.sessions.isEmpty() -> EmptyState(stringResource(R.string.sessions_empty), null)
                else -> LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp)) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(session, state.revoking) { viewModel.ask(session) }
                        Separator()
                    }
                }
            }
        }
    }
}
