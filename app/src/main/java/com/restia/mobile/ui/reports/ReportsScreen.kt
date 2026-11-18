package com.restia.mobile.ui.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.ui.checks.methodLabel
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.FilterTag
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.SectionTitle
import com.restia.mobile.ui.theme.readableWidth
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SPANISH = Locale.forLanguageTag("es-MX")
private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d", SPANISH)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", SPANISH)

@Composable
private fun reportsFailureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server && failure.status == 403 -> stringResource(R.string.error_action_forbidden)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Un número principal: etiqueta arriba, cifra grande abajo. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    GroupCard(modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Restia.colors.heading)
        }
    }
}

/**
 * Barras de una sola serie, ancladas a la base, con la punta redondeada y 2 dp entre barras. Tocar una barra
 * la marca y [onSelect] muestra su valor en texto; el valor exacto nunca depende solo del color.
 */
@Composable
private fun BarChart(
    values: List<Float>,
    labels: List<String>,
    labelEvery: Int,
    selected: Int?,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bar = Restia.colors.brand
    val dim = Restia.colors.brand.copy(alpha = 0.55f)
    val base = MaterialTheme.colorScheme.outlineVariant
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        val slot = size.width / values.size.coerceAtLeast(1)
                        onSelect((offset.x / slot).toInt().coerceIn(0, values.lastIndex))
                    }
                },
        ) {
            val slot = size.width / values.size.coerceAtLeast(1)
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx().coerceAtMost((slot - gap) / 2)
            values.forEachIndexed { i, v ->
                if (v <= 0f) return@forEachIndexed
                val h = (v / max) * (size.height - 4.dp.toPx())
                val left = i * slot + gap / 2
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = size.height - h, right = left + slot - gap, bottom = size.height,
                            topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                }
                drawPath(path, if (selected == null || selected == i) bar else dim)
            }
            drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Spacer(Modifier.height(4.dp))
        // Cada etiqueta ocupa el ancho de las barras que cubre, así no se corta; arranca bajo la primera.
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.indices.chunked(labelEvery).forEach { chunk ->
                Text(
                    labels[chunk.first()],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (labelEvery == 1) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(chunk.size.toFloat()),
                )
            }
        }
    }
}

/** Fila de un método de pago: nombre y monto en texto, y una barra con su parte del total. */
@Composable
private fun MethodRow(label: String, amount: String, share: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(amount, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).background(Restia.colors.brand, MaterialTheme.shapes.extraSmall))
        }
    }
}

private val GROUPED = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(Locale.US))

/** "$13,611.00 MXN": en los reportes los montos son grandes y se leen mejor con separador de miles. */
fun reportMoney(amount: BigDecimal, currency: String): String = "$" + GROUPED.format(amount) + " " + currency

private fun money(amount: BigDecimal, currency: String) = reportMoney(amount, currency)

/** Reportes (gerente): ventas del periodo y a qué horas se llena el salón. */
@Composable
fun ReportsScreen(viewModel: ReportsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel) { viewModel.load() }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.reports_title), onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).readableWidth().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterTag(stringResource(R.string.reports_today), state.period == ReportPeriod.TODAY) { viewModel.select(ReportPeriod.TODAY) }
                FilterTag(stringResource(R.string.reports_week), state.period == ReportPeriod.WEEK) { viewModel.select(ReportPeriod.WEEK) }
                FilterTag(stringResource(R.string.reports_month), state.period == ReportPeriod.MONTH) { viewModel.select(ReportPeriod.MONTH) }
            }
            state.failure?.let { Text(reportsFailureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val s = state.summary
            when {
                s == null && state.loading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() }
                s == null -> Unit
                s.empty -> EmptyState(stringResource(R.string.reports_empty), stringResource(R.string.reports_empty_hint), modifier = Modifier.height(320.dp))
                else -> ReportBody(s, state.period)
            }
        }
    }
}

@Composable
private fun ReportBody(s: com.restia.mobile.ui.reports.ReportSummary, period: ReportPeriod) {
    s.asOf?.let { iso ->
        val time = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
        if (time != null) Text(stringResource(R.string.reports_as_of, time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(stringResource(R.string.reports_net), money(s.net, s.currency), Modifier.weight(1f))
        StatTile(stringResource(R.string.reports_checks), s.checks.toString(), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(stringResource(R.string.reports_ticket), s.averageTicket?.let { money(it, s.currency) } ?: "—", Modifier.weight(1f))
        StatTile(stringResource(R.string.reports_guests), s.guests.toString(), Modifier.weight(1f))
    }
    Text(
        stringResource(R.string.reports_extras, money(s.tips, s.currency), money(s.refunds, s.currency), money(s.courtesies, s.currency)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (period != ReportPeriod.TODAY && s.days.size > 1) {
        SectionTitle(stringResource(R.string.reports_by_day))
        var selected by remember(s) { mutableStateOf<Int?>(null) }
        val best = s.days.maxByOrNull { it.net }
        val every = if (s.days.size <= 7) 1 else 5
        BarChart(
            values = s.days.map { it.net.toFloat() },
            labels = s.days.map { if (s.days.size <= 7) it.date.format(SHORT_DAY).replace(".", "") else it.date.format(DAY_MONTH).replace(".", "") },
            labelEvery = every,
            selected = selected,
            onSelect = { selected = it },
            description = stringResource(R.string.reports_by_day_description, best?.date?.format(DAY_MONTH)?.replace(".", "") ?: "", money(best?.net ?: BigDecimal.ZERO, s.currency)),
        )
        val caption = selected?.let { i -> s.days.getOrNull(i) }?.let { d ->
            stringResource(R.string.reports_day_value, d.date.format(DAY_MONTH).replace(".", ""), money(d.net, s.currency))
        } ?: best?.takeIf { it.net.signum() > 0 }?.let { d ->
            stringResource(R.string.reports_best_day, d.date.format(DAY_MONTH).replace(".", ""), money(d.net, s.currency))
        }
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (s.byMethod.isNotEmpty()) {
        SectionTitle(stringResource(R.string.reports_by_method))
        s.byMethod.forEach { m -> MethodRow(stringResource(methodLabel(m.method)), money(m.sales, s.currency), m.share) }
    }

    SectionTitle(stringResource(R.string.reports_by_hour))
    if (s.hours.isEmpty()) {
        Text(stringResource(R.string.reports_no_occupancy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        var selected by remember(s) { mutableStateOf<Int?>(null) }
        val every = when {
            s.hours.size <= 8 -> 1
            s.hours.size <= 16 -> 2
            else -> 3
        }
        val peak = s.peak
        BarChart(
            values = s.hours.map { it.tables.toFloat() },
            labels = s.hours.map { "${it.hour}h" },
            labelEvery = every,
            selected = selected,
            onSelect = { selected = it },
            description = peak?.let { stringResource(R.string.reports_peak, it.hour, it.tables.toPlainString()) } ?: "",
        )
        val caption = selected?.let { i -> s.hours.getOrNull(i) }?.let { h ->
            pluralStringResource(R.plurals.reports_hour_value, h.sessions, h.hour, h.tables.toPlainString(), h.sessions)
        } ?: peak?.let { stringResource(R.string.reports_peak, it.hour, it.tables.toPlainString()) }
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (period != ReportPeriod.TODAY) {
            Text(stringResource(R.string.reports_average_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
