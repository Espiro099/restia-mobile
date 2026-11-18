package com.restia.mobile.ui.orders

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewModelScope
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.KitchenUnitDto
import com.restia.mobile.data.kitchen.KitchenRepository
import com.restia.mobile.ui.tables.waitText
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenHeader
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.StatusPill
import com.restia.mobile.ui.theme.readableWidth
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Un platillo de una mesa en la cola: cuántas unidades van en cada estado. */
data class ActiveLine(val name: String, val pending: Int, val preparing: Int, val ready: Int) {
    val total: Int get() = pending + preparing + ready
}

/** Lo que una mesa tiene en cocina: pendiente, en preparación o listo para servir. */
data class TableOrders(val tableNumber: Int, val oldest: String, val lines: List<ActiveLine>) {
    val ready: Int get() = lines.sumOf { it.ready }
    val total: Int get() = lines.sumOf { it.total }

    /** Paso del seguimiento (0 recibida, 1 en preparación, 2 lista): el de la unidad más atrasada. */
    val stage: Int get() = when {
        lines.any { it.pending > 0 } -> 0
        lines.any { it.preparing > 0 } -> 1
        else -> 2
    }
}

/**
 * Agrupa la cola por mesa (la primera, si la comanda une varias) y por platillo, de la mesa que más espera a la
 * que menos. Lo que no tiene mesa (para llevar) no aparece: esta vista es del salón.
 */
fun tableOrders(units: List<KitchenUnitDto>): List<TableOrders> =
    units.filter { it.tableNumbers.isNotEmpty() }
        .groupBy { it.tableNumbers.first() }
        .map { (number, rows) ->
            val lines = rows.groupBy { it.productName }.map { (name, same) ->
                ActiveLine(
                    name,
                    pending = same.count { it.unit.status == "PENDING" },
                    preparing = same.count { it.unit.status == "PREPARING" },
                    ready = same.count { it.unit.status == "READY" },
                )
            }
            TableOrders(number, rows.minOf { it.unit.createdAt }, lines)
        }
        .sortedBy { it.oldest }

/** Minutos desde que la mesa pidió; null si la hora no se entiende. */
fun minutesSince(iso: String, now: OffsetDateTime = OffsetDateTime.now()): Long? =
    runCatching { Duration.between(OffsetDateTime.parse(iso), now).toMinutes().coerceAtLeast(0) }.getOrNull()

data class ActiveOrdersUiState(
    val units: List<KitchenUnitDto> = emptyList(),
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val tables: List<TableOrders> get() = tableOrders(units)
}

/** La cola de la sucursal, vista desde el salón: alimenta la pestaña Comandas y el aviso de "orden lista". */
class ActiveOrdersViewModel(
    private val repository: KitchenRepository,
    private val branchId: String,
    private val pollMillis: Long = POLL_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow(ActiveOrdersUiState())
    val state: StateFlow<ActiveOrdersUiState> = _state

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

    private suspend fun load() {
        _state.value = when (val r = repository.queue(branchId, null)) {
            is ApiResult.Success -> _state.value.copy(units = r.value, loaded = true, failure = null)
            is ApiResult.Failure -> _state.value.copy(failure = r.failure)
        }
    }

    class Factory(private val repository: KitchenRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ActiveOrdersViewModel(repository, branchId) as T
    }

    private companion object {
        const val POLL_MILLIS = 10_000L
    }
}

/** Texto del paso de una mesa en la cola. */
fun stageLabel(stage: Int): Int = when (stage) {
    0 -> R.string.track_received
    1 -> R.string.track_preparing
    else -> R.string.track_ready
}

@Composable
private fun TableOrdersCard(orders: TableOrders, onClick: (() -> Unit)?) {
    val tone = when (orders.stage) {
        0 -> MaterialTheme.colorScheme.onSurfaceVariant
        1 -> Restia.colors.busy
        else -> Restia.colors.free
    }
    GroupCard(modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.table_title, orders.tableNumber),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                StatusPill(stringResource(stageLabel(orders.stage)), tone)
            }
            minutesSince(orders.oldest)?.let {
                Text(
                    stringResource(R.string.orders_waiting, waitText(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Separator()
            orders.lines.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.total.toString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
                    Text(line.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (line.ready > 0) {
                        Text(
                            pluralStringResource(R.plurals.unit_ready, line.ready, line.ready),
                            style = MaterialTheme.typography.labelMedium,
                            color = Restia.colors.free,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pestaña Comandas (propuesta 09): lo que cada mesa tiene en cocina, la que más espera primero. Sin nada en
 * cocina, el estado vacío lleva a las mesas.
 */
@Composable
fun ActiveOrdersScreen(
    viewModel: ActiveOrdersViewModel,
    onOpenTable: ((tableNumber: Int) -> Unit)?,
    onGoToTables: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsState()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.tab_orders))
        Column(modifier = Modifier.fillMaxSize().readableWidth()) {
            state.failure?.let {
                Text(
                    stringResource(if (state.loaded) R.string.tables_stale else R.string.orders_not_loaded),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
                )
            }
            val tables = state.tables
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && tables.isEmpty() -> EmptyState(
                    stringResource(R.string.orders_empty_title),
                    stringResource(R.string.orders_empty_body),
                    action = onGoToTables?.let { stringResource(R.string.orders_go_to_tables) },
                    onAction = { onGoToTables?.invoke() },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(tables, key = { it.tableNumber }) { orders ->
                        TableOrdersCard(orders, onOpenTable?.let { open -> { open(orders.tableNumber) } })
                    }
                }
            }
        }
    }
}
