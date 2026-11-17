package com.restia.mobile.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restia.mobile.R

/** La flama con la palabra "Restia". En tamaño grande lleva el lema debajo, como en la portada. */
@Composable
fun RestiaLogo(modifier: Modifier = Modifier, height: Dp = 28.dp, tagline: Boolean = false, color: Color = Restia.colors.brandDeep) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_restia_flame), contentDescription = null, modifier = Modifier.size(height))
            Spacer(Modifier.width(height / 5))
            Text(
                stringResource(R.string.app_name),
                style = WordmarkStyle.copy(fontSize = (height.value * 0.92f).sp),
                color = color,
            )
        }
        if (tagline) {
            Text(
                stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Medium),
                color = color.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Iniciales en un círculo terracota: el perfil sin foto. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials.ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.38f).sp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Título de sección en serif, como "Mi perfil" o "Notificaciones" en la propuesta. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

/** Fila de opción con ícono, texto y flecha: el menú "Más" y el perfil. */
@Composable
fun OptionRow(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).then(clickable).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (detail != null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Tarjeta clara sobre el fondo crema, para agrupar filas. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column { content() }
    }
}

/** Campana de servicio sobre su plato: la ilustración de los estados vacíos (propuesta 9). */
@Composable
fun Cloche(modifier: Modifier = Modifier, color: Color = Restia.colors.brand) {
    Canvas(modifier = modifier.size(width = 132.dp, height = 96.dp)) {
        val w = size.width
        val base = size.height * 0.8f
        val radius = w * 0.34f
        val dome = Size(radius * 2, radius * 2)
        val corner = Offset(w / 2 - radius, base - radius)
        drawArc(color.copy(alpha = 0.16f), startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = corner, size = dome)
        drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = corner, size = dome, style = Stroke(width = w * 0.03f))
        // Brillo del metal.
        drawArc(
            color.copy(alpha = 0.55f),
            startAngle = 200f,
            sweepAngle = 40f,
            useCenter = false,
            topLeft = Offset(corner.x + radius * 0.25f, corner.y + radius * 0.25f),
            size = Size(radius * 1.5f, radius * 1.5f),
            style = Stroke(width = w * 0.025f, cap = StrokeCap.Round),
        )
        drawCircle(color, radius = w * 0.04f, center = Offset(w / 2, base - radius - w * 0.03f))
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.04f, base),
            size = Size(w * 0.92f, size.height * 0.07f),
            cornerRadius = CornerRadius(size.height * 0.035f),
        )
    }
}

/** Estado vacío: la campana, un título y, si ayuda, qué hacer a continuación. */
@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cloche(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Restia.colors.brandDeep, textAlign = TextAlign.Center)
        body?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Etiqueta en píldora: el texto siempre dice el estado; el color solo lo refuerza. */
@Composable
fun StatusPill(text: String, tone: Color) {
    Surface(shape = CircleShape, color = tone.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (tone.luminance() > 0.45f) MaterialTheme.colorScheme.onSurface else tone,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
