package com.restia.mobile.ui.more

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.restia.mobile.R
import com.restia.mobile.ui.tables.roleLabel
import com.restia.mobile.ui.theme.Avatar
import com.restia.mobile.ui.theme.GroupCard
import com.restia.mobile.ui.theme.OptionRow
import com.restia.mobile.ui.theme.RestiaLogo
import com.restia.mobile.ui.theme.SectionTitle

/** Secciones principales de la barra inferior (propuesta: Inicio, Mesas, Órdenes, Más). */
enum class HomeTab(@DrawableRes val icon: Int, @StringRes val label: Int) {
    TABLES(R.drawable.ic_table, R.string.tab_tables),
    RESERVATIONS(R.drawable.ic_calendar, R.string.reservations_title),
    KITCHEN(R.drawable.ic_kitchen, R.string.kitchen_title),
    MORE(R.drawable.ic_more, R.string.tab_more),
}

/**
 * Pantallas principales con la barra inferior. Cada sección conserva su propio Scaffold; aquí solo se reserva el
 * espacio de la barra y se consumen sus márgenes, para que la sección no los aplique dos veces.
 */
@Composable
fun HomeScaffold(tabs: List<HomeTab>, selected: HomeTab, onSelect: (HomeTab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // Solo el margen inferior queda resuelto aquí; el de la barra de estado lo sigue aplicando cada sección.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(modifier = Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
            content()
        }
    }
}

/** Una opción del menú "Más": abre una pantalla de gestión (caja, impresiones, personal...). */
class MoreOption(@DrawableRes val icon: Int, @StringRes val label: Int, val onClick: () -> Unit)

/** "Más": el perfil del empleado (propuesta 7) y el acceso a lo que no cabe en la barra (propuesta 8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    userName: String,
    userEmail: String,
    role: String,
    branchName: String,
    options: List<MoreOption>,
    logoutEnabled: Boolean,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { RestiaLogo(height = 26.dp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionTitle(stringResource(R.string.profile_title), Modifier.widthIn(max = 560.dp).fillMaxWidth())
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(userName, size = 88.dp)
                Text(userName, style = MaterialTheme.typography.titleLarge)
                Text(roleLabel(role)?.let { stringResource(it) } ?: role, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GroupCard {
                    if (userEmail.isNotBlank()) {
                        OptionRow(R.drawable.ic_person, stringResource(R.string.profile_user), detail = userEmail)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    OptionRow(R.drawable.ic_group, stringResource(R.string.profile_role), detail = roleLabel(role)?.let { stringResource(it) } ?: role)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OptionRow(R.drawable.ic_place, stringResource(R.string.profile_branch), detail = branchName)
                }
                if (options.isNotEmpty()) {
                    GroupCard {
                        options.forEachIndexed { i, option ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            OptionRow(option.icon, stringResource(option.label), onClick = option.onClick)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onLogout,
                    enabled = logoutEnabled,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_logout), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.action_logout))
                }
            }
        }
    }
}
