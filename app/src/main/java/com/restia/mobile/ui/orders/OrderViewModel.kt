package com.restia.mobile.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.OrderCreateRequest
import com.restia.mobile.data.orders.CartLine
import com.restia.mobile.data.orders.MAX_NOTES
import com.restia.mobile.data.orders.MAX_QUANTITY
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.orders.SelectedOption
import com.restia.mobile.data.orders.addToCart
import com.restia.mobile.data.orders.invalidModifiers
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProductEditor(
    val product: MenuProductDto,
    val quantity: Int = 1,
    val selected: Map<String, Set<String>> = emptyMap(),
    val notes: String = "",
    val showInvalid: Boolean = false,
)

data class OrderUiState(
    val menu: MenuDto? = null,
    val menuFailure: ApiFailure? = null,
    val cart: List<CartLine> = emptyList(),
    val editor: ProductEditor? = null,
    val reviewing: Boolean = false,
    val sending: Boolean = false,
    /** El envío pudo llegar al servidor: la comanda queda bloqueada hasta resolverlo. */
    val uncertain: Boolean = false,
    val failure: ApiFailure? = null,
    val sent: Boolean = false,
)

class OrderViewModel(
    private val menuRepository: MenuRepository,
    private val orderRepository: OrderRepository,
    private val branchId: String,
    private val sessionId: String,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state

    private var request: OrderCreateRequest? = null
    private var idempotencyKey: String = ""

    init {
        loadMenu()
    }

    fun loadMenu() {
        viewModelScope.launch {
            _state.value = when (val r = menuRepository.load(branchId)) {
                is ApiResult.Success -> _state.value.copy(menu = r.value, menuFailure = null)
                is ApiResult.Failure -> _state.value.copy(menuFailure = r.failure)
            }
        }
    }

    fun selectProduct(product: MenuProductDto) {
        if (product.availability != "AVAILABLE" || _state.value.uncertain) return
        _state.value = _state.value.copy(editor = ProductEditor(product))
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editor = null)
    }

    fun setQuantity(quantity: Int) = updateEditor { it.copy(quantity = quantity.coerceIn(1, MAX_QUANTITY)) }

    fun setNotes(notes: String) = updateEditor { it.copy(notes = notes.take(MAX_NOTES)) }

    fun toggleOption(modifierId: String, optionId: String) = updateEditor { editor ->
        val modifier = _state.value.menu?.modifiers?.firstOrNull { it.id == modifierId } ?: return@updateEditor editor
        val current = editor.selected[modifierId].orEmpty()
        val next = when {
            optionId in current -> current - optionId
            modifier.maxSelections == 1 -> setOf(optionId)
            current.size < modifier.maxSelections -> current + optionId
            else -> current
        }
        editor.copy(selected = editor.selected + (modifierId to next))
    }

    fun modifiersOf(product: MenuProductDto) =
        _state.value.menu?.modifiers.orEmpty().filter { it.id in product.modifierIds }

    fun addEditorToCart() {
        val editor = _state.value.editor ?: return
        val modifiers = modifiersOf(editor.product)
        if (invalidModifiers(modifiers, editor.selected).isNotEmpty()) {
            updateEditor { it.copy(showInvalid = true) }
            return
        }
        val options = modifiers.flatMap { m ->
            m.options.filter { it.id in editor.selected[m.id].orEmpty() }.map { SelectedOption(m, it) }
        }
        val line = CartLine(editor.product, options, editor.quantity, editor.notes.trim().ifEmpty { null })
        _state.value = _state.value.copy(cart = addToCart(_state.value.cart, line), editor = null, failure = null)
    }

    fun removeLine(line: CartLine) {
        if (_state.value.uncertain || _state.value.sending) return
        val cart = _state.value.cart - line
        _state.value = _state.value.copy(cart = cart, reviewing = cart.isNotEmpty(), failure = null)
    }

    fun setReviewing(reviewing: Boolean) {
        if (!_state.value.sending) _state.value = _state.value.copy(reviewing = reviewing)
    }

    /**
     * Un reintento repite identificador y clave. Solo una comanda editada, que ya se sabe que
     * no entró, recibe identificadores nuevos (T-7, CLIENT_ORDER_CONFLICT).
     */
    fun send() {
        val state = _state.value
        if (state.sending || state.cart.isEmpty()) return
        val items = state.cart.map { it.toRequest() }
        val body = request?.takeIf { it.items == items } ?: OrderCreateRequest(sessionId, newUuid(), items).also {
            request = it
            idempotencyKey = newUuid()
        }
        _state.value = state.copy(sending = true, failure = null)
        viewModelScope.launch {
            _state.value = when (val r = orderRepository.create(branchId, idempotencyKey, body)) {
                is ApiResult.Success -> {
                    request = null
                    OrderUiState(menu = _state.value.menu, sent = true)
                }
                is ApiResult.Failure -> _state.value.copy(sending = false, failure = r.failure, uncertain = isUncertain(r.failure))
            }
            if ((_state.value.failure as? ApiFailure.Server)?.code == PRODUCT_UNAVAILABLE) loadMenu()
        }
    }

    /** Descarta una comanda en duda: si ya había entrado, seguirá en cocina. */
    fun discard() {
        if (_state.value.sending) return
        request = null
        _state.value = _state.value.copy(cart = emptyList(), reviewing = false, uncertain = false, failure = null)
    }

    fun consumeSent() {
        _state.value = _state.value.copy(sent = false)
    }

    private fun isUncertain(failure: ApiFailure): Boolean = when (failure) {
        ApiFailure.Network, ApiFailure.Unexpected -> true
        is ApiFailure.Server -> failure.status >= 500
    }

    private fun updateEditor(change: (ProductEditor) -> ProductEditor) {
        val editor = _state.value.editor ?: return
        _state.value = _state.value.copy(editor = change(editor))
    }

    class Factory(
        private val menu: MenuRepository,
        private val orders: OrderRepository,
        private val branchId: String,
        private val sessionId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderViewModel(menu, orders, branchId, sessionId) as T
    }

    private companion object {
        const val PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE"
    }
}
