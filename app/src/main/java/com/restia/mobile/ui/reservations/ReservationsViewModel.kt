package com.restia.mobile.ui.reservations

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.AvailabilityDto
import com.restia.mobile.core.network.CheckInRequest
import com.restia.mobile.core.network.QueueEntryDto
import com.restia.mobile.core.network.ReservationCreateRequest
import com.restia.mobile.core.network.ReservationCustomerDto
import com.restia.mobile.core.network.ReservationDto
import com.restia.mobile.core.network.TableDto
import com.restia.mobile.core.network.TableOptionDto
import com.restia.mobile.data.reservations.ReservationsRepository
import com.restia.mobile.data.tables.TablesRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PHONE = Regex("""^\+[1-9][0-9]{7,14}$""")

fun isValidPhone(phone: String): Boolean = PHONE.matches(phone.trim())

/** Instante ISO 8601 en UTC, como lo pide el contrato. */
fun isoInstant(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

/** Hora local de un instante del servidor, o null si no se puede leer. */
fun localTime(iso: String, zone: ZoneId): LocalTime? = runCatching { Instant.parse(iso).atZone(zone).toLocalTime() }.getOrNull()

/** Acciones que la reserva admite en su estado (Decisiones_Reservations, sección 2). */
fun canConfirm(r: ReservationDto) = r.status == "PENDING"
fun canArrive(r: ReservationDto) = r.status == "CONFIRMED"
fun canSeat(r: ReservationDto) = r.status == "CONFIRMED" || r.status == "ARRIVED_WAITING"
fun canCancel(r: ReservationDto) = r.status in setOf("PENDING", "CONFIRMED", "ARRIVED_WAITING")

/** NO_SHOW solo si está CONFIRMED y ya pasó su hora; el backend exige además la tolerancia de la sucursal (R-6). */
fun canMarkNoShow(r: ReservationDto, now: Instant): Boolean =
    r.status == "CONFIRMED" && runCatching { now.isAfter(Instant.parse(r.startAt)) }.getOrDefault(false)

@StringRes
fun reservationStatusLabel(status: String): Int = when (status) {
    "PENDING" -> R.string.reservation_pending
    "CONFIRMED" -> R.string.reservation_confirmed
    "ARRIVED_WAITING" -> R.string.reservation_arrived
    "CHECKED_IN" -> R.string.reservation_checked_in
    "COMPLETED" -> R.string.reservation_completed
    "CANCELLED" -> R.string.reservation_cancelled
    "NO_SHOW" -> R.string.reservation_no_show
    else -> R.string.check_unknown
}

data class CreateState(
    val date: LocalDate,
    val start: LocalTime,
    val durationMinutes: Int = 90,
    val partySize: Int = 2,
    val name: String = "",
    val phone: String = "+52",
    val notes: String = "",
    /** Confirmar en cuanto se crea: el caso de quien reserva por teléfono con el personal. */
    val confirmNow: Boolean = true,
    val availability: AvailabilityDto? = null,
    val checking: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && name.isNotBlank() && isValidPhone(phone)
}

data class SeatState(
    val reservation: ReservationDto,
    val options: List<TableOptionDto> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = true,
    /** El backend respondió RESERVATION_PRIORITY_CONFLICT: otra reserva llegó antes (R-8). */
    val priorityConflict: Boolean = false,
    val overrideReason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean
        get() = !submitting && !loading && selected in options.indices && (!priorityConflict || overrideReason.trim().length >= 3)
}

data class CancelReservationState(val reservation: ReservationDto, val reason: String = "", val submitting: Boolean = false, val failure: ApiFailure? = null) {
    val canSubmit: Boolean get() = !submitting && reason.trim().length in 3..500
}

/** Mesa abierta por un check-in: la pantalla pasa a la vista de la mesa. */
data class Seated(val sessionId: String, val tableNumber: Int)

data class Notice(@StringRes val text: Int, val arg: String? = null)

data class ReservationsUiState(
    val day: LocalDate,
    val reservations: List<ReservationDto> = emptyList(),
    val queue: List<QueueEntryDto> = emptyList(),
    val tables: Map<String, TableDto> = emptyMap(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    val creating: CreateState? = null,
    val seating: SeatState? = null,
    val cancelling: CancelReservationState? = null,
    val working: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val notice: Notice? = null,
    val seated: Seated? = null,
) {
    val busy: Boolean get() = working || creating?.submitting == true || seating?.submitting == true || cancelling?.submitting == true

    /** "Mesa 1 y 2" a partir de los ids; una mesa que ya no existe se omite. */
    fun tableNumbers(ids: List<String>): List<Int> = ids.mapNotNull { tables[it]?.number }.sorted()
}

class ReservationsViewModel(
    private val repository: ReservationsRepository,
    private val tablesRepository: TablesRepository,
    private val branchId: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = { Instant.now() },
    private val pollMillis: Long = POLL_MILLIS,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(ReservationsUiState(day = now().atZone(zone).toLocalDate()))
    val state: StateFlow<ReservationsUiState> = _state

    private var polling: Job? = null

    // Mismo intento, misma clave: un reintento tras perder la respuesta no repite la operación.
    private var intent: Any? = null
    private var intentKey = ""

    private fun keyFor(newIntent: Any): String {
        if (intent != newIntent) {
            intent = newIntent
            intentKey = newKey()
        }
        return intentKey
    }

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

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun consumeSeated() {
        _state.value = _state.value.copy(seated = null)
    }

    fun moveDay(days: Long) {
        _state.value = _state.value.copy(day = _state.value.day.plusDays(days), reservations = emptyList(), loaded = false)
        refresh()
    }

    private suspend fun load() {
        val day = _state.value.day
        if (_state.value.tables.isEmpty()) {
            (tablesRepository.list(branchId) as? ApiResult.Success)?.let { r -> _state.value = _state.value.copy(tables = r.value.associateBy { it.id }) }
        }
        val from = isoInstant(day.atStartOfDay(zone).toInstant())
        val to = isoInstant(day.plusDays(1).atStartOfDay(zone).toInstant())
        val list = repository.list(branchId, from, to)
        val queue = repository.queue(branchId)
        // Si cambió el día mientras cargaba, esta respuesta ya no aplica.
        if (_state.value.day != day) return
        _state.value = when (list) {
            is ApiResult.Success -> _state.value.copy(
                reservations = list.value,
                queue = (queue as? ApiResult.Success)?.value ?: _state.value.queue,
                loaded = true,
                failure = (queue as? ApiResult.Failure)?.failure,
            )
            is ApiResult.Failure -> _state.value.copy(failure = list.failure)
        }
    }

    // --- Nueva reserva ---

    fun startCreate() {
        if (_state.value.busy) return
        // Propone la siguiente media hora del día que se está viendo.
        val local = now().atZone(zone)
        val today = _state.value.day == local.toLocalDate()
        val start = if (today) local.toLocalTime().truncatedTo(ChronoUnit.HOURS).plusMinutes(if (local.minute < 30) 30 else 60) else LocalTime.of(14, 0)
        _state.value = _state.value.copy(creating = CreateState(date = _state.value.day, start = start), notice = null, actionFailure = null)
    }

    private fun editCreate(change: (CreateState) -> CreateState) {
        val current = _state.value.creating ?: return
        if (current.submitting) return
        // Cualquier cambio invalida la disponibilidad que se había consultado.
        _state.value = _state.value.copy(creating = change(current).copy(availability = null, failure = null))
    }

    fun shiftDate(days: Long) = editCreate { it.copy(date = it.date.plusDays(days).coerceAtLeast(now().atZone(zone).toLocalDate())) }
    fun setDate(date: LocalDate) = editCreate { it.copy(date = date.coerceAtLeast(now().atZone(zone).toLocalDate())) }
    fun shiftStart(minutes: Long) = editCreate { it.copy(start = it.start.plusMinutes(minutes)) }
    fun setStart(time: LocalTime) = editCreate { it.copy(start = time) }
    fun shiftDuration(minutes: Int) = editCreate { it.copy(durationMinutes = (it.durationMinutes + minutes).coerceIn(30, 360)) }
    fun setParty(size: Int) = editCreate { it.copy(partySize = size.coerceIn(1, 200)) }
    fun setName(name: String) = editCreate { it.copy(name = name.take(120)) }
    fun setPhone(phone: String) = editCreate { it.copy(phone = phone.filter { c -> c.isDigit() || c == '+' }.take(16)) }
    fun setNotes(notes: String) = editCreate { it.copy(notes = notes.take(500)) }
    fun setConfirmNow(value: Boolean) = editCreate { it.copy(confirmNow = value) }

    fun dismissCreate() {
        if (_state.value.creating?.submitting == true) return
        _state.value = _state.value.copy(creating = null)
    }

    private fun interval(c: CreateState): Pair<String, String> {
        val start = c.date.atTime(c.start).atZone(zone).toInstant()
        return isoInstant(start) to isoInstant(start.plusSeconds(c.durationMinutes * 60L))
    }

    fun checkAvailability() {
        val current = _state.value.creating ?: return
        if (current.checking || current.submitting) return
        val (start, end) = interval(current)
        _state.value = _state.value.copy(creating = current.copy(checking = true, failure = null))
        viewModelScope.launch {
            val r = repository.availability(branchId, start, end, current.partySize)
            val now = _state.value.creating ?: return@launch
            _state.value = _state.value.copy(
                creating = now.copy(checking = false, availability = (r as? ApiResult.Success)?.value, failure = (r as? ApiResult.Failure)?.failure),
            )
        }
    }

    fun confirmCreate() {
        val current = _state.value.creating ?: return
        if (!current.canSubmit) return
        val (start, end) = interval(current)
        val body = ReservationCreateRequest(
            startAt = start,
            endAt = end,
            partySize = current.partySize,
            customer = ReservationCustomerDto(name = current.name.trim(), phone = current.phone.trim()),
            notes = current.notes.trim().ifEmpty { null },
        )
        val key = keyFor(body)
        _state.value = _state.value.copy(creating = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val created = repository.create(branchId, key, body)) {
                is ApiResult.Failure -> {
                    if (!isRetryable(created.failure)) intent = null
                    _state.value = _state.value.copy(creating = _state.value.creating?.copy(submitting = false, failure = created.failure))
                }
                is ApiResult.Success -> {
                    intent = null
                    var notice = Notice(R.string.reservation_created, created.value.confirmationCode)
                    if (current.confirmNow) {
                        // Si no hay lugar, la reserva queda PENDING: no se pierde.
                        when (val confirmed = repository.confirm(branchId, created.value, newKey())) {
                            is ApiResult.Success -> notice = Notice(R.string.reservation_created_confirmed, confirmed.value.confirmationCode)
                            is ApiResult.Failure -> notice = Notice(R.string.reservation_created_not_confirmed, created.value.confirmationCode)
                        }
                    }
                    _state.value = _state.value.copy(creating = null, notice = notice, day = current.date)
                    load()
                }
            }
        }
    }

    // --- Acciones simples ---

    fun confirm(r: ReservationDto) = simple(r, "confirm", R.string.reservation_confirmed_done) { key -> repository.confirm(branchId, r, key) }

    fun arrive(r: ReservationDto) = simple(r, "arrive", R.string.reservation_arrived_done) { key -> repository.arrive(branchId, r, key) }

    fun markNoShow(r: ReservationDto) = simple(r, "no-show", R.string.reservation_no_show_done) { key -> repository.noShow(branchId, r, key) }

    private fun simple(r: ReservationDto, action: String, @StringRes done: Int, call: suspend (String) -> ApiResult<ReservationDto>) {
        if (_state.value.busy) return
        val key = keyFor(Triple(action, r.id, r.version))
        _state.value = _state.value.copy(working = true, actionFailure = null, notice = null)
        viewModelScope.launch {
            val result = call(key)
            val failure = (result as? ApiResult.Failure)?.failure
            if (failure == null || !isRetryable(failure)) intent = null
            _state.value = _state.value.copy(working = false, actionFailure = failure, notice = if (failure == null) Notice(done, r.customer.name) else null)
            load()
        }
    }

    // --- Cancelar ---

    fun startCancel(r: ReservationDto) {
        if (!canCancel(r) || _state.value.busy) return
        _state.value = _state.value.copy(cancelling = CancelReservationState(r), notice = null, actionFailure = null)
    }

    fun setCancelReason(reason: String) {
        val current = _state.value.cancelling ?: return
        if (!current.submitting) _state.value = _state.value.copy(cancelling = current.copy(reason = reason.take(500), failure = null))
    }

    fun dismissCancel() {
        if (_state.value.cancelling?.submitting != true) _state.value = _state.value.copy(cancelling = null)
    }

    fun confirmCancel() {
        val current = _state.value.cancelling ?: return
        if (!current.canSubmit) return
        val reason = current.reason.trim()
        val key = keyFor(Triple("cancel", current.reservation.id, reason))
        _state.value = _state.value.copy(cancelling = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.cancel(branchId, current.reservation, key, reason)) {
                is ApiResult.Success -> {
                    intent = null
                    _state.value = _state.value.copy(cancelling = null, notice = Notice(R.string.reservation_cancelled_done, current.reservation.customer.name))
                }
                is ApiResult.Failure -> {
                    if (!isRetryable(r.failure)) intent = null
                    _state.value = _state.value.copy(cancelling = current.copy(submitting = false, failure = r.failure))
                }
            }
            load()
        }
    }

    // --- Sentar ---

    /**
     * Opciones: las mesas planeadas primero; si ya llegó, las que calcula la fila de espera; si no, las que
     * estén libres desde ahora hasta su hora de fin.
     */
    fun startSeat(r: ReservationDto) {
        if (!canSeat(r) || _state.value.busy) return
        _state.value = _state.value.copy(seating = SeatState(r), notice = null, actionFailure = null)
        viewModelScope.launch {
            val options = mutableListOf<TableOptionDto>()
            if (r.plannedTableIds.isNotEmpty()) {
                options += TableOptionDto(r.plannedTableIds, r.plannedTableIds.sumOf { _state.value.tables[it]?.capacity ?: 0 })
            }
            val queued = _state.value.queue.firstOrNull { it.reservation.id == r.id }
            if (queued != null) {
                options += queued.tableOptions
            } else {
                val start = now()
                val end = maxOf(Instant.parse(r.endAt), start.plusSeconds(3600))
                (repository.availability(branchId, isoInstant(start), isoInstant(end), r.partySize) as? ApiResult.Success)?.let { options += it.value.tableOptions }
            }
            val unique = options.distinctBy { it.tableIds.sorted() }
            val seat = _state.value.seating ?: return@launch
            if (seat.reservation.id == r.id) _state.value = _state.value.copy(seating = seat.copy(options = unique, loading = false))
        }
    }

    fun selectOption(index: Int) {
        val current = _state.value.seating ?: return
        // Otras mesas pueden no chocar con quien llegó antes: la prioridad se vuelve a revisar.
        if (!current.submitting) _state.value = _state.value.copy(seating = current.copy(selected = index, failure = null, priorityConflict = false))
    }

    fun setOverrideReason(reason: String) {
        val current = _state.value.seating ?: return
        if (!current.submitting) _state.value = _state.value.copy(seating = current.copy(overrideReason = reason.take(500)))
    }

    fun dismissSeat() {
        if (_state.value.seating?.submitting != true) _state.value = _state.value.copy(seating = null)
    }

    fun confirmSeat() {
        val current = _state.value.seating ?: return
        if (!current.canSubmit) return
        val option = current.options[current.selected]
        val body = CheckInRequest(
            tableIds = option.tableIds,
            overridePriority = true.takeIf { current.priorityConflict },
            overrideReason = current.overrideReason.trim().takeIf { current.priorityConflict },
        )
        val key = keyFor(Pair(current.reservation.id, body))
        _state.value = _state.value.copy(seating = current.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.checkIn(branchId, current.reservation, key, body)) {
                is ApiResult.Success -> {
                    intent = null
                    val number = _state.value.tableNumbers(option.tableIds).firstOrNull() ?: 0
                    _state.value = _state.value.copy(seating = null, seated = r.value.sessionId?.let { Seated(it, number) })
                }
                is ApiResult.Failure -> {
                    if (!isRetryable(r.failure)) intent = null
                    val priority = (r.failure as? ApiFailure.Server)?.code == "RESERVATION_PRIORITY_CONFLICT"
                    _state.value = _state.value.copy(
                        seating = current.copy(submitting = false, failure = r.failure, priorityConflict = current.priorityConflict || priority),
                    )
                }
            }
            load()
        }
    }

    private fun isRetryable(failure: ApiFailure): Boolean =
        failure == ApiFailure.Network || (failure is ApiFailure.Server && failure.status >= 500)

    class Factory(
        private val repository: ReservationsRepository,
        private val tablesRepository: TablesRepository,
        private val branchId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReservationsViewModel(repository, tablesRepository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 20_000L
    }
}
