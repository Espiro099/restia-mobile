package com.restia.mobile.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.DailySalesDto
import com.restia.mobile.core.network.HourlyOccupancyDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.data.reports.ReportsRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Periodos que el gerente consulta en el piso; el contrato acepta hasta 92 días. */
enum class ReportPeriod(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

data class DaySales(val date: LocalDate, val net: BigDecimal)

data class MethodSales(val method: String, val sales: BigDecimal, val share: Float)

/** Mesas ocupadas en una hora: promedio por día del periodo, y sesiones abiertas en total. */
data class HourActivity(val hour: Int, val tables: BigDecimal, val sessions: Int)

data class ReportSummary(
    val currency: String,
    val net: BigDecimal,
    val tips: BigDecimal,
    val refunds: BigDecimal,
    val courtesies: BigDecimal,
    val checks: Int,
    val guests: Int,
    /** Venta neta entre cuentas cobradas; null sin cuentas. */
    val averageTicket: BigDecimal?,
    /** Un valor por día del periodo, con 0 en los días sin ventas. */
    val days: List<DaySales>,
    val byMethod: List<MethodSales>,
    /** Las horas de la primera a la última con actividad, sin huecos. */
    val hours: List<HourActivity>,
    /** Fecha y hora del cálculo más reciente (`asOf`); null si no hubo filas. */
    val asOf: String?,
) {
    val peak: HourActivity? get() = hours.filter { it.tables.signum() > 0 }.maxByOrNull { it.tables }
    val empty: Boolean get() = net.signum() == 0 && checks == 0 && hours.isEmpty()
}

private fun BigDecimal?.orZero() = this ?: BigDecimal.ZERO

private fun amount(m: MoneyDto): BigDecimal = runCatching { BigDecimal(m.amount) }.getOrDefault(BigDecimal.ZERO)

fun periodRange(period: ReportPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = today.minusDays(period.days - 1L) to today

/** Junta las filas del periodo en lo que muestra la pantalla. */
fun summarize(sales: List<DailySalesDto>, occupancy: List<HourlyOccupancyDto>, from: LocalDate, to: LocalDate): ReportSummary {
    val currency = sales.firstOrNull()?.netSales?.currency ?: "MXN"
    val net = sales.fold(BigDecimal.ZERO) { acc, d -> acc + amount(d.netSales) }
    val checks = sales.sumOf { it.checkCount }
    val byDate = sales.associateBy({ it.businessDate }, { amount(it.netSales) })
    val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        .map { DaySales(it, byDate[it.toString()].orZero()) }.toList()
    val methods = sales.flatMap { it.byMethod }.groupBy { it.method }
        .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, m -> acc + amount(m.sales) } }
        .filterValues { it.signum() > 0 }
    val methodTotal = methods.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val byMethod = methods.entries.sortedByDescending { it.value }.map { (method, value) ->
        MethodSales(method, value, if (methodTotal.signum() > 0) value.divide(methodTotal, 4, RoundingMode.HALF_UP).toFloat() else 0f)
    }
    val dayCount = days.size.coerceAtLeast(1)
    val perHour = occupancy.groupBy { it.hour }
    val active = perHour.keys.filter { h -> perHour[h].orEmpty().any { it.tablesOccupied > 0 || it.sessionsOpened > 0 } }
    val hours = if (active.isEmpty()) {
        emptyList()
    } else {
        (active.min()..active.max()).map { h ->
            val rows = perHour[h].orEmpty()
            HourActivity(
                h,
                BigDecimal(rows.sumOf { it.tablesOccupied }).divide(BigDecimal(dayCount), 1, RoundingMode.HALF_UP),
                rows.sumOf { it.sessionsOpened },
            )
        }
    }
    val asOf = (sales.map { it.asOf } + occupancy.map { it.asOf }).maxOrNull()
    return ReportSummary(
        currency = currency,
        net = net,
        tips = sales.fold(BigDecimal.ZERO) { acc, d -> acc + amount(d.tips) },
        refunds = sales.fold(BigDecimal.ZERO) { acc, d -> acc + amount(d.refunds) },
        courtesies = sales.fold(BigDecimal.ZERO) { acc, d -> acc + amount(d.courtesies) },
        checks = checks,
        guests = sales.sumOf { it.guestCount },
        averageTicket = if (checks > 0) net.divide(BigDecimal(checks), 2, RoundingMode.HALF_UP) else null,
        days = days,
        byMethod = byMethod,
        hours = hours,
        asOf = asOf,
    )
}

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.TODAY,
    val summary: ReportSummary? = null,
    val loading: Boolean = false,
    val failure: ApiFailure? = null,
)

class ReportsViewModel(
    private val repository: ReportsRepository,
    private val branchId: String,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) : ViewModel() {
    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state
    private var job: Job? = null

    fun load() = select(_state.value.period, force = true)

    fun select(period: ReportPeriod, force: Boolean = false) {
        if (!force && period == _state.value.period && _state.value.summary != null) return
        job?.cancel()
        // Al cambiar de periodo no se muestran los números del anterior.
        val keep = if (period == _state.value.period) _state.value.summary else null
        _state.value = _state.value.copy(period = period, summary = keep, loading = true, failure = null)
        val (from, to) = periodRange(period, today())
        job = viewModelScope.launch {
            _state.value = when (val r = repository.load(branchId, from, to)) {
                is ApiResult.Success -> _state.value.copy(summary = summarize(r.value.sales, r.value.occupancy, from, to), loading = false)
                is ApiResult.Failure -> _state.value.copy(loading = false, failure = r.failure)
            }
        }
    }

    class Factory(private val repository: ReportsRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReportsViewModel(repository, branchId) as T
    }
}
