package com.restia.mobile.ui.more

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.ui.tables.roleLabel
import com.restia.mobile.ui.theme.Avatar
import com.restia.mobile.ui.theme.GroupLabel
import com.restia.mobile.ui.theme.OptionRow
import com.restia.mobile.ui.theme.Restia
import com.restia.mobile.ui.theme.ScreenHeader
import com.restia.mobile.ui.theme.ScreenPadding
import com.restia.mobile.ui.theme.Separator

/** Secciones de la barra inferior (propuesta: Mesas, Comandas, Cocina y Más). */
enum class HomeTab(@DrawableRes val icon: Int, @StringRes val label: Int) {
    TABLES(R.drawable.ic_table, R.string.tab_tables),
    ORDERS(R.drawable.ic_receipt, R.string.tab_orders),
    KITCHEN(R.drawable.ic_kitchen, R.string.kitchen_title),
    MORE(R.drawable.ic_more, R.string.tab_more),
}

/** Medidas de la navegación elevada (Figma "Barra con recorte circular"). */
private object Nav {
    val Height = 100.dp
    val BarTop = 30.dp
    val Corner = 16.dp
    val Circle = 56.dp
    val NotchHalf = 42.dp
    val NotchDepth = 31.dp
}

/**
 * La barra: un rectángulo redondeado con un recorte bajo la opción activa, donde se asienta su círculo. [center]
 * es la posición del recorte, de 0 a 1 a lo ancho, para poder animarla al cambiar de sección.
 */
private class NotchedBar(private val center: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val top = Nav.BarTop.toPx()
        val r = Nav.Corner.toPx()
        val body = Path().apply { addRoundRect(RoundRect(Rect(0f, top, size.width, size.height), r, r)) }
        val cx = size.width * center
        val half = Nav.NotchHalf.toPx()
        val depth = top + Nav.NotchDepth.toPx()
        // El mismo cuenco del diseño: entra suave desde el borde y baja hasta rodear el círculo.
        val notch = Path().apply {
            moveTo(cx - half, top - 1f)
            cubicTo(cx - half * 0.76f, top, cx - half * 0.83f, depth, cx, depth)
            cubicTo(cx + half * 0.83f, depth, cx + half * 0.76f, top, cx + half, top - 1f)
            close()
        }
        Outline.Generic(Path.combine(PathOperation.Difference, body, notch))
    }
}

/** Navegación elevada (propuesta): la opción activa sube en un círculo con su ícono en naranja. */
@Composable
fun ElevatedNavBar(tabs: List<HomeTab>, selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    val center by animateFloatAsState((index + 0.5f) / tabs.size, tween(220), label = "recorte")
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
            .height(Nav.Height),
    ) {
        Surface(shape = NotchedBar(center), color = paper, shadowElevation = 2.dp, modifier = Modifier.fillMaxSize()) {}
        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier.size(Nav.Circle).clip(CircleShape).background(paper),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(tab.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(13.dp))
                    } else {
                        Spacer(Modifier.height(39.dp))
                        Icon(painterResource(tab.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(7.dp))
                    }
                    Text(
                        stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
                        color = if (active) Restia.colors.heading else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Pantallas principales con la barra inferior. Cada sección pone su encabezado; aquí solo se reserva el espacio
 * de la barra y se consume ese margen, para que la sección no lo aplique dos veces.
 */
@Composable
fun HomeScaffold(tabs: List<HomeTab>, selected: HomeTab, onSelect: (HomeTab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ElevatedNavBar(tabs, selected, onSelect) },
    ) { padding ->
        // Solo el margen inferior queda resuelto aquí; el de la barra de estado lo aplica cada sección.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(modifier = Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            content()
        }
    }
}

/** Una herramienta del menú "Más": abre una pantalla de gestión (caja, impresiones, personal...). */
class MoreOption(@DrawableRes val icon: Int, @StringRes val label: Int, val onClick: () -> Unit)

/** "Más" (propuesta 07): el empleado, su cuenta y las herramientas que no caben en la barra. */
@Composable
fun MoreScreen(
    userName: String,
    userEmail: String,
    role: String,
    branchName: String,
    tools: List<MoreOption>,
    onChangePassword: () -> Unit,
    logoutEnabled: Boolean,
    onLogout: () -> Unit,
) {
    val roleText = roleLabel(role)?.let { stringResource(it) } ?: role
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.tab_more))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(userName)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(userName, style = MaterialTheme.typography.titleLarge, color = Restia.colors.heading)
                    Text("$roleText · $branchName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            GroupLabel(stringResource(R.string.more_account))
            if (userEmail.isNotBlank()) {
                OptionRow(R.drawable.ic_person, stringResource(R.string.profile_title), detail = userEmail)
                Separator()
            }
            OptionRow(R.drawable.ic_lock, stringResource(R.string.password_change_title), detail = stringResource(R.string.more_security), onClick = onChangePassword)
            Separator()
            OptionRow(R.drawable.ic_place, stringResource(R.string.profile_branch), detail = branchName)
            Separator()
            if (tools.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                GroupLabel(stringResource(R.string.more_tools))
                tools.forEach { option -> OptionRow(option.icon, stringResource(option.label), onClick = option.onClick) }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = logoutEnabled, role = Role.Button, onClick = onLogout)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_logout), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.action_logout),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (logoutEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
