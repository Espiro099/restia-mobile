package com.restia.mobile.ui.reservations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.core.network.CustomerDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-MX"))

/**
 * Lo que importa al recibir a alguien: visitas, notas (alergias) y preferencias. La ficha completa del
 * cliente (historial, edición) está en el panel web.
 */
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
