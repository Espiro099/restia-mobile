package com.restia.mobile.ui.notifications

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.NotificationDto
import com.restia.mobile.ui.theme.BackHeader
import com.restia.mobile.ui.theme.EmptyState
import com.restia.mobile.ui.theme.GroupLabel
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator
import com.restia.mobile.ui.theme.Tone
import com.restia.mobile.ui.theme.readableWidth
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Día al que pertenece el aviso, en la zona del dispositivo. */
fun noticeDay(n: NotificationDto, zone: ZoneId = ZoneId.systemDefault()): LocalDate? = noticeInstant(n)?.atZone(zone)?.toLocalDate()

/** Avisos agrupados por día, del más reciente al más antiguo; lo que no tiene fecha válida va al final. */
fun groupByDay(items: List<NotificationDto>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate?, List<NotificationDto>>> =
    items.groupBy { noticeDay(it, zone) }.toList().sortedWith(compareByDescending(nullsFirst()) { it.first })

@StringRes
fun noticeFailureText(failure: ApiFailure): Int? = when {
    failure == ApiFailure.Network -> R.string.error_network
    failure !is ApiFailure.Server -> R.string.error_unexpected
    failure.status >= 500 -> R.string.error_server
    failure.status == 403 -> R.string.error_action_forbidden
    else -> null
}

private val SPANISH = Locale.forLanguageTag("es-MX")

@Composable
private fun dayLabel(day: LocalDate?): String {
    if (day == null) return stringResource(R.string.notices_earlier)
    val today = LocalDate.now()
    val date = day.format(DateTimeFormatter.ofPattern("d MMMM", SPANISH))
    return when (day) {
        today -> stringResource(R.string.notices_today, date)
        today.minusDays(1) -> stringResource(R.string.notices_yesterday, date)
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMMM", SPANISH)).replace(".", "")
    }
}

private class NoticeLook(@DrawableRes val icon: Int, val tone: Tone)

@Composable
private fun noticeLook(type: String): NoticeLook {
    val tones = Restia.colors.tables
    return when (type) {
        "ORDER_READY" -> NoticeLook(R.drawable.ic_cloche, tones.waiting)
        "CHECK_REQUESTED" -> NoticeLook(R.drawable.ic_receipt, tones.noOrder)
        "PAYMENT_CONFIRMED" -> NoticeLook(R.drawable.ic_state_check, tones.eating)
        "PAYMENT_FAILED" -> NoticeLook(R.drawable.ic_info, tones.waiting)
        "LOW_STOCK" -> NoticeLook(R.drawable.ic_box, tones.waiting)
        else -> NoticeLook(R.drawable.ic_info, tones.noOrder)
    }
}

@Composable
private fun NoticeRow(notice: NotificationDto, onClick: () -> Unit) {
    val look = noticeLook(notice.type)
    val unread = notice.readAt == null
    val time = noticeInstant(notice)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(look.tone.container, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(look.icon), contentDescription = null, tint = look.tone.content, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                notice.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium),
                color = if (unread) Restia.colors.heading else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(notice.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (unread) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).background(Restia.colors.brand, MaterialTheme.shapes.extraLarge))
        }
    }
}

/** Avisos (propuesta 06): lo que espera atención, por día, con "Marcar leídos". */
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onBack: () -> Unit, onOpen: (NotificationDto) -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(stringResource(R.string.notices_title), onBack = onBack)
        Column(modifier = Modifier.weight(1f).readableWidth()) {
            Row(modifier = Modifier.padding(horizontal = ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.notices_pending),
                    style = MaterialTheme.typography.titleLarge,
                    color = Restia.colors.heading,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::markAllRead, enabled = state.unread > 0 && !state.markingAll) {
                    Text(stringResource(R.string.notices_mark_all), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
            state.failure?.let {
                Text(
                    noticeFailureText(it)?.let { id -> stringResource(id) } ?: (it as? ApiFailure.Server)?.message ?: stringResource(R.string.error_unexpected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
            when {
                !state.loaded && state.failure == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.loaded && state.items.isEmpty() -> EmptyState(stringResource(R.string.notices_empty), stringResource(R.string.notices_empty_hint))
                else -> LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = 24.dp)) {
                    groupByDay(state.items).forEach { (day, notices) ->
                        item(key = "dia-$day") { GroupLabel(dayLabel(day), Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                        items(notices, key = { it.id }) { notice ->
                            NoticeRow(notice) {
                                viewModel.markRead(notice)
                                onOpen(notice)
                            }
                            Separator()
                        }
                    }
                }
            }
        }
    }
}
