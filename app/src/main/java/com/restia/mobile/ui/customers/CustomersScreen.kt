package com.restia.mobile.ui.customers

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.ui.reservations.fieldMessage
import com.restia.mobile.ui.reservations.isValidPhone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-MX"))

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 403 -> stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server && failure.code == "VERSION_CONFLICT" -> stringResource(R.string.customer_conflict)
    failure is ApiFailure.Server && failure.status == 422 -> fieldMessage(failure) ?: failure.message ?: stringResource(R.string.error_unexpected)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Lo que importa al recibir a alguien: visitas, notas (alergias) y preferencias. */
@Composable
fun CustomerSummary(c: CustomerDto, zone: ZoneId = ZoneId.systemDefault()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleMedium)
        c.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        c.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val last = c.profile.lastVisitAt?.let { runCatching { Instant.parse(it).atZone(zone).format(DATE) }.getOrNull() }
        Text(
            pluralStringResource(R.plurals.customer_visits, c.profile.visitCount, c.profile.visitCount) +
                (last?.let { " · " + stringResource(R.string.customer_last_visit, it) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        c.profile.notes?.let { Text(stringResource(R.string.kitchen_notes, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (c.profile.preferences.isNotEmpty()) Text(c.profile.preferences.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FormDialog(f: CustomerForm, viewModel: CustomersViewModel) {
    val enabled = !f.submitting
    AlertDialog(
        onDismissRequest = viewModel::dismissForm,
        title = { Text(stringResource(if (f.original == null) R.string.customer_new else R.string.customer_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.name, viewModel::setName, label = { Text(stringResource(R.string.reservation_name)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    f.phone, viewModel::setPhone, label = { Text(stringResource(R.string.reservation_phone)) }, singleLine = true, enabled = enabled,
                    isError = f.phone.length > 3 && !isValidPhone(f.phone), supportingText = { Text(stringResource(R.string.reservation_phone_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(f.email, viewModel::setEmail, label = { Text(stringResource(R.string.customer_email)) }, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.notes, viewModel::setNotes, label = { Text(stringResource(R.string.customer_notes)) },
                    supportingText = { Text(stringResource(R.string.customer_notes_hint)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.preferences, viewModel::setPreferences, label = { Text(stringResource(R.string.customer_preferences)) },
                    supportingText = { Text(stringResource(R.string.customer_preferences_hint)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                f.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::submit, enabled = f.canSubmit) { Text(stringResource(R.string.customer_save)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissForm, enabled = enabled) { Text(stringResource(R.string.cancel_back)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(viewModel: CustomersViewModel, canCreate: Boolean, canUpdate: Boolean, openCustomerId: String?, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(openCustomerId) { openCustomerId?.let(viewModel::open) }
    val selected = state.selected
    // Atrás cierra primero el cliente abierto; si se llegó desde una reserva, vuelve a ella.
    BackHandler { if (selected != null && openCustomerId == null) viewModel.closeSelected() else onBack() }
    state.form?.let { FormDialog(it, viewModel) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customers_title)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.reservations_title)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            if (state.saved) Text(stringResource(R.string.customer_saved))
            when {
                state.loadingSelected -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                selected != null -> {
                    Card {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomerSummary(selected)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (canUpdate && !selected.anonymized) Button(onClick = viewModel::startEdit) { Text(stringResource(R.string.customer_edit)) }
                                if (openCustomerId == null) OutlinedButton(onClick = viewModel::closeSelected) { Text(stringResource(R.string.customer_back_to_search)) }
                            }
                        }
                    }
                }
                else -> {
                    OutlinedTextField(
                        state.query, viewModel::setQuery,
                        label = { Text(stringResource(R.string.customer_search)) },
                        supportingText = { Text(stringResource(R.string.customer_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::search, enabled = !state.searching && searchRequest(state.query) != null) { Text(stringResource(R.string.customer_search_action)) }
                        if (canCreate) OutlinedButton(onClick = viewModel::startCreate) { Text(stringResource(R.string.customer_new)) }
                    }
                    when {
                        state.searching -> CircularProgressIndicator()
                        state.searched && state.results.isEmpty() -> Text(stringResource(R.string.customer_none))
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.results, key = { it.id }) { c ->
                            Card(onClick = { viewModel.select(c) }) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) { CustomerSummary(c) }
                            }
                        }
                    }
                }
            }
        }
    }
}
