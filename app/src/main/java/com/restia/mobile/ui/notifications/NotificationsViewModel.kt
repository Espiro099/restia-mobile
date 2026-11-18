package com.restia.mobile.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.NotificationDto
import com.restia.mobile.data.notifications.NotificationsRepository
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val items: List<NotificationDto> = emptyList(),
    val unread: Int = 0,
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    val markingAll: Boolean = false,
)

/** Hora local del aviso, para agruparlo por día; null si el servidor mandó algo que no se entiende. */
fun noticeInstant(n: NotificationDto): Instant? = runCatching { OffsetDateTime.parse(n.updatedAt).toInstant() }.getOrNull()

/**
 * Avisos del usuario. Se consultan cada cierto tiempo mientras el plano o la lista están a la vista;
 * marcar uno como leído se refleja de inmediato y, si falla, la siguiente consulta lo corrige.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    private val branchId: String,
    private val pollMillis: Long = POLL_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state

    private var polling: Job? = null

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

    private suspend fun load() {
        _state.value = when (val r = repository.list(branchId)) {
            is ApiResult.Success -> _state.value.copy(items = r.value.items, unread = r.value.unread, loaded = true, failure = null)
            // Se conservan los últimos avisos; el error se muestra arriba de la lista.
            is ApiResult.Failure -> _state.value.copy(failure = r.failure)
        }
    }

    /** Al abrir un aviso queda leído. */
    fun markRead(notice: NotificationDto) {
        if (notice.readAt != null) return
        val now = Instant.now().toString()
        _state.value = _state.value.copy(
            items = _state.value.items.map { if (it.id == notice.id) it.copy(readAt = now) else it },
            unread = (_state.value.unread - 1).coerceAtLeast(0),
        )
        viewModelScope.launch { repository.read(branchId, notice.id) }
    }

    fun markAllRead() {
        if (_state.value.markingAll || _state.value.unread == 0) return
        _state.value = _state.value.copy(markingAll = true, failure = null)
        viewModelScope.launch {
            when (val r = repository.readAll(branchId)) {
                is ApiResult.Success -> {
                    val now = Instant.now().toString()
                    _state.value = _state.value.copy(items = _state.value.items.map { it.copy(readAt = it.readAt ?: now) }, unread = 0, markingAll = false)
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(markingAll = false, failure = r.failure)
            }
        }
    }

    class Factory(private val repository: NotificationsRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NotificationsViewModel(repository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 15_000L
    }
}
