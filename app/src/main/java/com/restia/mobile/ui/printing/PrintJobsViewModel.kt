package com.restia.mobile.ui.printing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.data.printing.PrintingRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** printing.resolve: reintentar, resolver y cancelar. printing.reprint: reimprimir. */
data class PrintPermissions(val resolve: Boolean, val reprint: Boolean)

enum class PrintTab { ATTENTION, RECENT }

/** Qué se le hace a un trabajo con formulario; reintentar no lo necesita. */
enum class PrintActionKind { RESOLVE, CANCEL, REPRINT }

/** Estados en que el backend acepta cada acción (Decisiones_Printing, sección 2). */
fun canRetry(job: PrintJobDto) = job.status == "FAILED"

fun canResolve(job: PrintJobDto) = job.status == "UNKNOWN"

fun canCancel(job: PrintJobDto) = job.status in setOf("PENDING", "FAILED", "UNKNOWN")

fun canReprint(job: PrintJobDto) = job.status in setOf("PRINTED", "UNKNOWN")

data class PrintAction(
    val job: PrintJobDto,
    val kind: PrintActionKind,
    /** Solo al resolver: true si salió impreso. */
    val printed: Boolean? = null,
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && reason.trim().length >= 3 && (kind != PrintActionKind.RESOLVE || printed != null)
}

data class Notice(@StringRes val text: Int)

data class PrintJobsUiState(
    val tab: PrintTab = PrintTab.ATTENTION,
    val jobs: List<PrintJobDto> = emptyList(),
    val printers: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: ApiFailure? = null,
    val action: PrintAction? = null,
    /** Trabajo que se está reintentando. */
    val retrying: String? = null,
    val actionFailure: ApiFailure? = null,
    val notice: Notice? = null,
)

class PrintJobsViewModel(
    private val repository: PrintingRepository,
    private val branchId: String,
    val can: PrintPermissions,
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(PrintJobsUiState())
    val state: StateFlow<PrintJobsUiState> = _state

    private var polling: Job? = null

    // Misma reimpresión, misma clave: si se pierde la respuesta, no sale dos veces.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

    /** Los trabajos cambian solos (PENDING → PRINTED): mientras la pantalla está visible se actualiza. */
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                load()
                delay(pollMillis)
            }
        }
    }

    fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun selectTab(tab: PrintTab) {
        if (tab == _state.value.tab) return
        _state.value = _state.value.copy(tab = tab, jobs = emptyList(), loaded = false, failure = null)
        refresh()
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** Se pidió otra carga mientras una estaba en curso: lo traído ya puede no incluir el último cambio. */
    private var reloadAgain = false

    /**
     * Una sola carga a la vez. Si otra llega a la mitad (sondeo, Actualizar o tras una acción), no se pierde:
     * se repite al terminar, para que lo recién reimpreso o cancelado aparezca de inmediato.
     */
    private suspend fun load() {
        if (_state.value.loading) {
            reloadAgain = true
            return
        }
        _state.value = _state.value.copy(loading = true)
        try {
            do {
                reloadAgain = false
                val tab = _state.value.tab
                if (_state.value.printers.isEmpty()) {
                    (repository.printerNames(branchId) as? ApiResult.Success)?.let { _state.value = _state.value.copy(printers = it.value) }
                }
                val r = if (tab == PrintTab.ATTENTION) repository.attention(branchId) else repository.recent(branchId)
                // Si cambió de pestaña mientras cargaba, esta respuesta ya no sirve: se carga la nueva.
                if (_state.value.tab != tab) {
                    reloadAgain = true
                    continue
                }
                _state.value = when (r) {
                    is ApiResult.Success -> _state.value.copy(jobs = r.value, loaded = true, failure = null)
                    is ApiResult.Failure -> _state.value.copy(failure = r.failure)
                }
            } while (reloadAgain)
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    // --- Reintentar: sin formulario ---

    fun retry(job: PrintJobDto) {
        if (!can.resolve || !canRetry(job) || _state.value.retrying != null) return
        _state.value = _state.value.copy(retrying = job.id, actionFailure = null, notice = null)
        viewModelScope.launch {
            val r = repository.retry(branchId, job)
            _state.value = _state.value.copy(
                retrying = null,
                actionFailure = (r as? ApiResult.Failure)?.failure,
                notice = if (r is ApiResult.Success) Notice(R.string.print_retry_done) else null,
            )
            load()
        }
    }

    // --- Resolver, cancelar y reimprimir: con motivo ---

    fun start(job: PrintJobDto, kind: PrintActionKind) {
        val allowed = when (kind) {
            PrintActionKind.RESOLVE -> can.resolve && canResolve(job)
            PrintActionKind.CANCEL -> can.resolve && canCancel(job)
            PrintActionKind.REPRINT -> can.reprint && canReprint(job)
        }
        if (!allowed) return
        _state.value = _state.value.copy(action = PrintAction(job, kind), actionFailure = null, notice = null)
    }

    fun edit(transform: (PrintAction) -> PrintAction) {
        val action = _state.value.action ?: return
        if (action.submitting) return
        _state.value = _state.value.copy(action = transform(action).copy(failure = null))
    }

    fun dismiss() {
        if (_state.value.action?.submitting == true) return
        _state.value = _state.value.copy(action = null)
    }

    fun submit() {
        val action = _state.value.action ?: return
        if (!action.canSubmit) return
        val reason = action.reason.trim()
        _state.value = _state.value.copy(action = action.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val r = when (action.kind) {
                PrintActionKind.RESOLVE -> repository.resolve(branchId, action.job, action.printed == true, reason)
                PrintActionKind.CANCEL -> repository.cancel(branchId, action.job, reason)
                PrintActionKind.REPRINT -> repository.reprint(branchId, action.job, keyFor(Pair(action.job.id, reason)), reason)
            }
            when (r) {
                is ApiResult.Success -> {
                    val notice = when (action.kind) {
                        PrintActionKind.RESOLVE -> if (action.printed == true) R.string.print_resolved_printed else R.string.print_resolved_not_printed
                        PrintActionKind.CANCEL -> R.string.print_cancel_done
                        PrintActionKind.REPRINT -> R.string.print_reprint_done
                    }
                    _state.value = _state.value.copy(action = null, notice = Notice(notice))
                    load()
                }
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(action = action.copy(submitting = false, failure = r.failure))
                    // Cambió mientras tanto (salió, lo resolvió otro): se muestra lo vigente.
                    val code = (r.failure as? ApiFailure.Server)?.code
                    if (code == "VERSION_CONFLICT" || code == "PRINT_JOB_INVALID_STATE") load()
                }
            }
        }
    }

    class Factory(private val repository: PrintingRepository, private val branchId: String, private val can: PrintPermissions) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PrintJobsViewModel(repository, branchId, can) as T
    }

    companion object {
        const val POLL_MILLIS = 8_000L
    }
}
