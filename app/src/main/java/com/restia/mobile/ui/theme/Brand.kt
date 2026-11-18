package com.restia.mobile.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.restia.mobile.R

/** Margen lateral de las pantallas de la propuesta. */
val ScreenPadding = 24.dp

/**
 * La marca original (flama y "Restia"), tal como viene del diseño: a color en claro y en blanco en oscuro
 * (drawable-night). Con `tagline`, el lema debajo, como en la bienvenida.
 */
@Composable
fun RestiaLogo(modifier: Modifier = Modifier, height: Dp = 34.dp, tagline: Boolean = false) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.drawable.restia_logo),
            contentDescription = stringResource(R.string.app_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(height),
        )
        if (tagline) {
            Text(
                stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Iniciales sobre arena: el perfil sin foto. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials.ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp),
            color = Restia.colors.heading,
        )
    }
}

/** Encabezado de una sección principal ("Más", "Comandas"): título a la izquierda y, si hay, una acción. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = ScreenPadding, end = 12.dp, top = 10.dp, bottom = 12.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
            color = Restia.colors.heading,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/**
 * Encabezado de una pantalla de detalle (propuesta 03–05): flecha de regreso, título y acciones a la derecha.
 * Sin título, [center] ocupa el centro (la marca en el seguimiento de la orden).
 */
@Composable
fun BackHeader(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    center: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp).heightIn(min = 48.dp)) {
        Row(modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.nav_back),
                    tint = Restia.colors.heading,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    color = Restia.colors.heading,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions()
        }
        center?.let { Box(Modifier.align(Alignment.Center)) { it() } }
    }
}

/** Título de sección ("Mesas", "Mi perfil"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = Restia.colors.heading,
        modifier = modifier.semantics { heading() },
    )
}

/** Rótulo de grupo en mayúsculas ("MI CUENTA", "HERRAMIENTAS"). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** Fila de opción: ícono de línea, texto (y detalle debajo) y flecha si abre algo. */
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
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).then(clickable).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = if (detail != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Separador de las listas: línea de arena. */
@Composable
fun Separator(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Tarjeta de papel con borde de arena, para agrupar contenido. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

/** Botón principal: naranja, 48 de alto y esquinas de 12. Con `trailing`, un importe a la derecha. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    trailing: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            trailing != null -> {
                Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(trailing, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            }
            else -> Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Botón secundario: contorno y texto naranja ("Ver cuenta de Mesa 5"). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Etiqueta de filtro ("Todas · 9", "Salón · 6"): naranja la elegida, papel las demás. */
@Composable
fun FilterTag(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Tab, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics { this.selected = selected },
    )
}

/** Campana de servicio sobre su plato (ilustración de la propuesta). */
@Composable
fun Cloche(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_cloche), contentDescription = null, modifier = modifier.size(width = 88.dp, height = 72.dp))
}

/** Estado vacío (propuesta 09): la campana, un título, qué pasará y, si ayuda, una acción. */
@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Cloche(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Restia.colors.heading)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null) PrimaryButton(action, onAction, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Etiqueta de estado ("En preparación", "Cuenta solicitada"): el texto dice el estado; el color lo refuerza. */
@Composable
fun StatusPill(text: String, tone: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = tone.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (tone.luminance() > 0.45f) MaterialTheme.colorScheme.onSurface else tone,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Borde de una tarjeta de estado: punteado cuando el estado aún no está confirmado o está pendiente. */
fun Modifier.toneBorder(tone: Tone, shape: Shape, radius: Dp): Modifier =
    if (!tone.dashed) {
        border(1.dp, tone.border, shape)
    } else {
        drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = tone.border,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
    }

/** Ancho máximo de una pantalla de lectura (mesa, comanda, cuenta): en tablet queda centrada, no estirada. */
val ReadableWidth = 720.dp

/** Ocupa todo el ancho en teléfono; en pantallas anchas se limita a [ReadableWidth] y se centra. */
fun Modifier.readableWidth(): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ReadableWidth)
