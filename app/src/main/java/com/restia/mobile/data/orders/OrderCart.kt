package com.restia.mobile.data.orders

import com.restia.mobile.core.network.MenuModifierDto
import com.restia.mobile.core.network.MenuOptionDto
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.OrderItemRequest
import com.restia.mobile.core.network.OrderModifierOption
import java.math.BigDecimal

const val MAX_QUANTITY = 50
const val MAX_LINES = 100
const val MAX_NOTES = 500

data class SelectedOption(val modifier: MenuModifierDto, val option: MenuOptionDto)

data class CartLine(
    val product: MenuProductDto,
    val options: List<SelectedOption>,
    val quantity: Int,
    val notes: String?,
) {
    /** Solo orientativo: el servidor calcula el total real con sus precios. */
    val estimate: BigDecimal
        get() = options.fold(BigDecimal(product.price.amount)) { sum, o -> sum + BigDecimal(o.option.priceDelta.amount) } * BigDecimal(quantity)

    fun toRequest() = OrderItemRequest(
        productId = product.id,
        quantity = quantity,
        modifierOptions = options.map { OrderModifierOption(it.modifier.id, it.option.id) }.takeIf { it.isNotEmpty() },
        notes = notes,
    )
}

/** Devuelve los grupos que no cumplen su mínimo o máximo. */
fun invalidModifiers(modifiers: List<MenuModifierDto>, selected: Map<String, Set<String>>): List<MenuModifierDto> =
    modifiers.filter { (selected[it.id]?.size ?: 0) !in it.minSelections..it.maxSelections }

/** Une líneas iguales, salvo que lleven notas o superen el máximo por partida. */
fun addToCart(cart: List<CartLine>, line: CartLine): List<CartLine> {
    val key = { l: CartLine -> l.product.id to l.options.map { it.option.id }.toSet() }
    val index = cart.indexOfFirst {
        it.notes == null && line.notes == null && key(it) == key(line) && it.quantity + line.quantity <= MAX_QUANTITY
    }
    if (index < 0) return if (cart.size < MAX_LINES) cart + line else cart
    return cart.toMutableList().also { it[index] = it[index].copy(quantity = it[index].quantity + line.quantity) }
}
