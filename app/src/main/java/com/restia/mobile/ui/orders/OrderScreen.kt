package com.restia.mobile.ui.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.restia.mobile.R
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ImageClient
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.data.orders.CartLine
import com.restia.mobile.data.orders.MAX_QUANTITY
import com.restia.mobile.data.orders.invalidModifiers
import com.restia.mobile.ui.theme.Restia
import java.math.BigDecimal
import java.math.RoundingMode

fun moneyText(amount: BigDecimal, currency: String): String =
    "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency

private fun moneyText(money: MoneyDto): String =
    runCatching { moneyText(BigDecimal(money.amount), money.currency) }.getOrDefault(money.amount)

@Composable
private fun failureText(failure: ApiFailure): String = when {
    failure == ApiFailure.Network -> stringResource(R.string.error_network)
    failure is ApiFailure.Server && failure.status >= 500 -> stringResource(R.string.error_server)
    failure is ApiFailure.Server -> failure.message ?: stringResource(R.string.error_unexpected)
    else -> stringResource(R.string.error_unexpected)
}

/** Tonos de la miniatura: cada categoría conserva el suyo mientras no hay fotos de los platillos. */
@Composable
private fun categoryTone(category: String): Color {
    val tones = listOf(Restia.colors.brand, Restia.colors.free, Restia.colors.busy, Restia.colors.brandDeep)
    return tones[Math.floorMod(category.hashCode(), tones.size)]
}

/** En gris: la foto de un platillo agotado. */
private val SoldOutFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Miniatura del platillo (propuesta 3): la foto si la hay; debajo, la inicial sobre el color de su categoría,
 * que es lo que queda a la vista mientras carga, si falla o si no hay foto.
 */
@Composable
private fun DishThumb(product: MenuProductDto, available: Boolean) {
    val tone = categoryTone(product.category)
    Box(
        modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium).background(tone.copy(alpha = if (available) 0.18f else 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            product.name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.headlineMedium,
            color = if (available) tone else MaterialTheme.colorScheme.outline,
        )
        if (ImageClient.isAllowed(product.imageUrl)) {
            // Decorativa: el nombre del platillo ya está escrito junto a ella.
            AsyncImage(
                model = product.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Opaca, para tapar la inicial; lo agotado va en gris.
                colorFilter = if (available) null else SoldOutFilter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ProductRow(product: MenuProductDto, onClick: () -> Unit) {
    val available = product.availability == "AVAILABLE"
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(enabled = available, role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DishThumb(product, available)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                product.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (available) moneyText(product.price) else stringResource(R.string.order_sold_out),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (available) {
                // Decorativo: toda la tarjeta abre el platillo; el botón solo lo señala.
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun EditorDialog(viewModel: OrderViewModel, editor: ProductEditor) {
    val modifiers = viewModel.modifiersOf(editor.product)
    val invalid = invalidModifiers(modifiers, editor.selected)
    AlertDialog(
        onDismissRequest = viewModel::dismissEditor,
        title = { Text(editor.product.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { viewModel.setQuantity(editor.quantity - 1) }, enabled = editor.quantity > 1) { Text("−") }
                    Text(editor.quantity.toString(), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { viewModel.setQuantity(editor.quantity + 1) }, enabled = editor.quantity < MAX_QUANTITY) { Text("+") }
                }
                modifiers.forEach { modifier ->
                    Text(modifier.name, style = MaterialTheme.typography.titleSmall)
                    if (editor.showInvalid && modifier in invalid) {
                        Text(
                            stringResource(R.string.order_modifier_range, modifier.minSelections, modifier.maxSelections),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    modifier.options.forEach { option ->
                        val selected = option.id in editor.selected[modifier.id].orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleOption(modifier.id, option.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = { viewModel.toggleOption(modifier.id, option.id) })
                            Text(option.name + "  " + moneyText(option.priceDelta))
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.notes,
                    onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.order_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::addEditorToCart) { Text(stringResource(R.string.order_add)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditor) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CartLineRow(line: CartLine, locked: Boolean, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.quantity.toString() + " × " + line.product.name)
            line.options.forEach { Text(it.option.name, style = MaterialTheme.typography.bodySmall) }
            line.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (!locked) TextButton(onClick = onRemove) { Text(stringResource(R.string.order_remove)) }
    }
}

@Composable
private fun ReviewDialog(viewModel: OrderViewModel, state: OrderUiState) {
    val currency = state.cart.firstOrNull()?.product?.price?.currency.orEmpty()
    val estimate = runCatching { state.cart.fold(BigDecimal.ZERO) { sum, l -> sum + l.estimate } }.getOrNull()
    AlertDialog(
        onDismissRequest = { viewModel.setReviewing(false) },
        title = { Text(stringResource(R.string.order_review)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.cart.forEach { line ->
                    CartLineRow(line, state.uncertain || state.sending || state.readOnly) { viewModel.removeLine(line) }
                }
                HorizontalDivider()
                estimate?.let { Text(stringResource(R.string.order_estimate, moneyText(it, currency))) }
                if (state.readOnly) Text(stringResource(R.string.order_read_only), color = MaterialTheme.colorScheme.error)
                if (state.uncertain) {
                    Text(stringResource(R.string.order_uncertain), color = MaterialTheme.colorScheme.error)
                } else {
                    state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::send, enabled = !state.sending) {
                Text(stringResource(if (state.uncertain) R.string.order_retry else R.string.order_send))
            }
        },
        dismissButton = {
            if (state.uncertain) {
                TextButton(onClick = viewModel::discard, enabled = !state.sending) { Text(stringResource(R.string.order_discard)) }
            } else {
                TextButton(onClick = { viewModel.setReviewing(false) }, enabled = !state.sending) { Text(stringResource(R.string.order_keep_adding)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(viewModel: OrderViewModel, tableNumber: Int, onBack: () -> Unit, onSent: () -> Unit) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(state.sent) {
        if (state.sent) {
            viewModel.consumeSent()
            onSent()
        }
    }
    // null = todas las categorías.
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    state.editor?.let { EditorDialog(viewModel, it) }
    if (state.reviewing) ReviewDialog(viewModel, state)
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.order_title, tableNumber)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (state.cart.isNotEmpty()) {
                val currency = state.cart.first().product.price.currency
                val estimate = runCatching { state.cart.fold(BigDecimal.ZERO) { sum, l -> sum + l.estimate } }.getOrNull()
                Button(
                    onClick = { viewModel.setReviewing(true) },
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) {
                    Text(stringResource(R.string.order_view_cart, state.cart.sumOf { it.quantity }), modifier = Modifier.weight(1f))
                    estimate?.let { Text(moneyText(it, currency), style = MaterialTheme.typography.titleSmall) }
                }
            }
        },
    ) { padding ->
        val menu = state.menu
        when {
            menu != null -> {
                val categories = menu.products.map { it.category }.distinct()
                val selected = category?.takeIf { it in categories }
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (categories.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { CategoryChip(stringResource(R.string.order_all), selected == null) { category = null } }
                        items(categories) { name -> CategoryChip(name, selected == name) { category = name } }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.menuStale || state.readOnly) {
                        item(key = "avisos") {
                            Column {
                                if (state.menuStale) Text(stringResource(R.string.order_menu_stale), color = MaterialTheme.colorScheme.error)
                                if (state.readOnly) Text(stringResource(R.string.order_read_only), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    menu.products.filter { selected == null || it.category == selected }.groupBy { it.category }.forEach { (name, products) ->
                        item(key = "c-$name") {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                color = Restia.colors.brandDeep,
                                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                            )
                        }
                        items(products.sortedBy { it.displayOrder }, key = { it.id }) { product ->
                            ProductRow(product) { viewModel.selectProduct(product) }
                        }
                    }
                }
                }
            }
            state.menuFailure != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.order_menu_failed) + " " + failureText(state.menuFailure!!))
                Button(onClick = viewModel::loadMenu) { Text(stringResource(R.string.action_retry)) }
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
