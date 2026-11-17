package com.restia.mobile.ui.printing

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.PrintJobDto
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es-MX"))

private fun localTime(iso: String?): String? =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrNull() }

@StringRes
fun printTypeLabel(type: String): Int = when (type) {
    "KITCHEN_TICKET" -> R.string.print_type_kitchen
    "VOID_NOTICE" -> R.string.print_type_void
    "PRE_CHECK" -> R.string.print_type_pre_check
    "PAYMENT_RECEIPT" -> R.string.print_type_receipt
    "CASH_SHIFT_REPORT" -> R.string.print_type_cash_report
    "TEST" -> R.string.print_type_test
    else -> R.string.print_type_other
}

@StringRes
fun printStatusLabel(status: String): Int = when (status) {
    "PENDING" -> R.string.print_status_pending
    "CLAIMED", "PRINTING" -> R.string.print_status_printing
    "PRINTED" -> R.string.print_status_printed
    "FAILED" -> R.string.print_status_failed
    "UNKNOWN" -> R.string.print_status_unknown
    "CANCELLED" -> R.string.print_status_cancelled
    else -> R.string.print_type_other
}

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 429 && failure.retryAfterSeconds != null -> {
        val seconds = failure.retryAfterSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.error_too_many, seconds, seconds)
    }
    failure is ApiFailure.Server && failure.code == "VERSION_CONFLICT" -> stringResource(R.string.print_conflict)
    failure is ApiFailure.Server && failure.status == 403 -> failure.message ?: stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

@Composable
private fun ActionDialog(action: PrintAction, viewModel: PrintJobsViewModel) {
    val title = when (action.kind) {
        PrintActionKind.RESOLVE -> R.string.print_resolve_title
        PrintActionKind.CANCEL -> R.string.print_cancel_title
        PrintActionKind.REPRINT -> R.string.print_reprint_title
    }
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(printTypeLabel(action.job.type)), fontWeight = FontWeight.SemiBold)
                when (action.kind) {
                    PrintActionKind.RESOLVE -> {
                        Text(stringResource(R.string.print_resolve_body))
                        listOf(true to R.string.print_resolve_printed, false to R.string.print_resolve_not_printed).forEach { (value, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(selected = action.printed == value, enabled = !action.submitting, role = Role.RadioButton) { viewModel.edit { it.copy(printed = value) } },
                            ) {
                                RadioButton(selected = action.printed == value, onClick = null, enabled = !action.submitting)
                                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
                            }
                        }
                    }
                    PrintActionKind.CANCEL -> Text(stringResource(R.string.print_cancel_body))
                    PrintActionKind.REPRINT -> Text(stringResource(R.string.print_reprint_body))
                }
                OutlinedTextField(
                    value = action.reason,
                    onValueChange = { v -> viewModel.edit { it.copy(reason = v.take(500)) } },
                    label = { Text(stringResource(R.string.print_reason)) },
                    enabled = !action.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                action.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::submit, enabled = action.canSubmit) {
                if (action.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (action.kind == PrintActionKind.REPRINT) R.string.print_reprint else R.string.print_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !action.submitting) { Text(stringResource(R.string.action_back)) } },
    )
}

@Composable
private fun JobCard(job: PrintJobDto, state: PrintJobsUiState, viewModel: PrintJobsViewModel) {
    val can = viewModel.can
    val alert = job.status == "FAILED" || job.status == "UNKNOWN"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(printTypeLabel(job.type)) + if (job.originalJobId != null) " · " + stringResource(R.string.print_is_reprint) else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(printStatusLabel(job.status)),
                color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (alert) FontWeight.SemiBold else null,
            )
            val printer = job.printerId?.let { state.printers[it] }
            val details = listOfNotNull(printer, localTime(job.createdAt), job.attempts.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.print_attempts, it, it) })
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            job.lastError?.takeIf { alert }?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
            if (job.status == "UNKNOWN") Text(stringResource(R.string.print_unknown_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (can.resolve && canRetry(job)) {
                    TextButton(onClick = { viewModel.retry(job) }, enabled = state.retrying == null) {
                        if (state.retrying == job.id) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.print_retry))
                    }
                }
                if (can.resolve && canResolve(job)) TextButton(onClick = { viewModel.start(job, PrintActionKind.RESOLVE) }) { Text(stringResource(R.string.print_resolve)) }
                if (can.reprint && canReprint(job)) TextButton(onClick = { viewModel.start(job, PrintActionKind.REPRINT) }) { Text(stringResource(R.string.print_reprint)) }
                if (can.resolve && canCancel(job)) {
                    TextButton(onClick = { viewModel.start(job, PrintActionKind.CANCEL) }) {
                        Text(stringResource(R.string.print_cancel), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintJobsScreen(viewModel: PrintJobsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    val listState = rememberLazyListState()
    // La lista va del más reciente al más antiguo. Al insertar arriba, LazyColumn conserva el elemento visible
    // y el trabajo nuevo (una reimpresión, un fallo) quedaba fuera de la vista: si se estaba al principio, se sube.
    LaunchedEffect(state.jobs.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(6_000)
            viewModel.clearNotice()
        }
    }
    state.action?.let { ActionDialog(it, viewModel) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.print_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text(stringResource(R.string.action_refresh)) } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(selected = state.tab == PrintTab.ATTENTION, onClick = { viewModel.selectTab(PrintTab.ATTENTION) }, text = { Text(stringResource(R.string.print_tab_attention)) })
                Tab(selected = state.tab == PrintTab.RECENT, onClick = { viewModel.selectTab(PrintTab.RECENT) }, text = { Text(stringResource(R.string.print_tab_recent)) })
            }
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.print_not_loaded) + " " + failureText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.actionFailure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            state.notice?.let { Text(stringResource(it.text), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.jobs.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(stringResource(if (state.tab == PrintTab.ATTENTION) R.string.print_nothing_pending else R.string.print_empty))
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.jobs, key = { it.id }) { JobCard(it, state, viewModel) }
                }
            }
        }
    }
}
