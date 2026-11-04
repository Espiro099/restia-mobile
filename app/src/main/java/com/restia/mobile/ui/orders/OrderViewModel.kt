package com.restia.mobile.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuDto
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.core.network.OrderCreateRequest
import com.restia.mobile.core.network.OrderItemRequest
import com.restia.mobile.core.storage.ContactTracker
import com.restia.mobile.data.orders.CartLine
import com.restia.mobile.data.orders.DraftRepository
import com.restia.mobile.data.orders.MAX_NOTES
import com.restia.mobile.data.orders.MAX_QUANTITY
import com.restia.mobile.data.orders.MenuRepository
import com.restia.mobile.data.orders.OrderDraft
import com.restia.mobile.data.orders.OrderRepository
import com.restia.mobile.data.orders.SelectedOption
import com.restia.mobile.data.orders.addToCart
import com.restia.mobile.data.orders.invalidModifiers
import java.time.Instant
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
    /** El borrador guardado ya se restauró: antes de esto no se edita ni se envía. */
    val ready: Boolean = false,
    val menu: MenuDto? = null,
    val menuStale: Boolean = false,
    val menuFailure: ApiFailure? = null,
    val cart: List<CartLine> = emptyList(),
    val editor: ProductEditor? = null,
    val reviewing: Boolean = false,
    val sending: Boolean = false,
    /** El envío pudo llegar al servidor: la comanda queda bloqueada hasta resolverlo. */
    val uncertain: Boolean = false,
    /** Más de 12 h sin conexión: solo lectura hasta sincronizar (A-7). */
    val readOnly: Boolean = false,
    val failure: ApiFailure? = null,
    val sent: Boolean = false,
)

class OrderViewModel(
    private val menuRepository: MenuRepository,
    private val orderRepository: OrderRepository,
    private val drafts: DraftRepository,
    private val contact: ContactTracker,
    private val branchId: String,
    private val userId: String,
    private val sessionId: String,
    private val tableNumber: Int,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state

    private var capturedAt: String? = null
    private var clientOrderUuid: String? = null
    private var idempotencyKey: String? = null
    private var requestItems: List<OrderItemRequest>? = null

    init {
        viewModelScope.launch { restore() }
        loadMenu()
    }

    private suspend fun restore() {
        val draft = drafts.get(userId, sessionId)
        if (draft != null) {
            capturedAt = draft.capturedAt
            clientOrderUuid = draft.clientOrderUuid
            idempotencyKey = draft.idempotencyKey
            requestItems = draft.requestItems
        }
        _state.value = _state.value.copy(
            ready = true,
            readOnly = contact.windowExpired(),
            cart = draft?.lines.orEmpty(),
            uncertain = draft?.attempted == true,
            reviewing = draft?.attempted == true,
        )
    }

    fun loadMenu() {
        viewModelScope.launch {
            _state.value = when (val r = menuRepository.load(branchId)) {
                is ApiResult.Success -> _state.value.copy(menu = r.value.menu, menuStale = r.value.stale, menuFailure = null, readOnly = contact.windowExpired())
                is ApiResult.Failure -> _state.value.copy(menuFailure = r.failure, readOnly = contact.windowExpired())
            }
        }
    }

    private val editable: Boolean
        get() = _state.value.let { it.ready && !it.uncertain && !it.readOnly && !it.sending }

    fun selectProduct(product: MenuProductDto) {
        if (product.availability != "AVAILABLE" || !editable) return
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
        if (!editable) return
        val modifiers = modifiersOf(editor.product)
        if (invalidModifiers(modifiers, editor.selected).isNotEmpty()) {
            updateEditor { it.copy(showInvalid = true) }
            return
        }
        val options = modifiers.flatMap { m ->
            m.options.filter { it.id in editor.selected[m.id].orEmpty() }.map { SelectedOption(m, it) }
        }
        val line = CartLine(editor.product, options, editor.quantity, editor.notes.trim().ifEmpty { null })
        if (capturedAt == null) capturedAt = now().toString()
        _state.value = _state.value.copy(cart = addToCart(_state.value.cart, line), editor = null, failure = null)
        persist()
    }

    fun removeLine(line: CartLine) {
        if (!editable) return
        val cart = _state.value.cart - line
        _state.value = _state.value.copy(cart = cart, reviewing = cart.isNotEmpty(), failure = null)
        persist()
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
        if (!state.ready || state.sending || state.cart.isEmpty()) return
        val items = state.cart.map { it.toRequest() }
        if (requestItems != items || clientOrderUuid == null || idempotencyKey == null) {
            clientOrderUuid = newUuid()
            idempotencyKey = newUuid()
            requestItems = items
        }
        val at = capturedAt ?: now().toString().also { capturedAt = it }
        val body = OrderCreateRequest(sessionId, clientOrderUuid!!, at, items)
        val key = idempotencyKey!!
        _state.value = state.copy(sending = true, failure = null)
        viewModelScope.launch {
            // Se guarda antes de enviar: si la app muere aquí, al volver queda en duda y no se duplica.
            drafts.save(draft(state.cart, attempted = true))
            when (val r = orderRepository.create(branchId, key, body)) {
                is ApiResult.Success -> {
                    drafts.delete(userId, sessionId)
                    clearIdentifiers()
                    _state.value = OrderUiState(ready = true, menu = _state.value.menu, menuStale = _state.value.menuStale, sent = true)
                }
                is ApiResult.Failure -> {
                    val uncertain = isUncertain(r.failure)
                    if (!uncertain) drafts.save(draft(state.cart, attempted = false))
                    _state.value = _state.value.copy(sending = false, failure = r.failure, uncertain = uncertain, readOnly = contact.windowExpired())
                    if ((r.failure as? ApiFailure.Server)?.code == PRODUCT_UNAVAILABLE) loadMenu()
                }
            }
        }
    }

    /** Descarta una comanda en duda: si ya había entrado, seguirá en cocina. */
    fun discard() {
        if (_state.value.sending) return
        clearIdentifiers()
        _state.value = _state.value.copy(cart = emptyList(), reviewing = false, uncertain = false, failure = null)
        viewModelScope.launch { drafts.delete(userId, sessionId) }
    }

    fun consumeSent() {
        _state.value = _state.value.copy(sent = false)
    }

    private fun clearIdentifiers() {
        capturedAt = null
        clientOrderUuid = null
        idempotencyKey = null
        requestItems = null
    }

    private fun draft(cart: List<CartLine>, attempted: Boolean) = OrderDraft(
        userId = userId,
        branchId = branchId,
        sessionId = sessionId,
        tableNumber = tableNumber,
        capturedAt = capturedAt ?: now().toString(),
        lines = cart,
        clientOrderUuid = clientOrderUuid,
        idempotencyKey = idempotencyKey,
        requestItems = requestItems,
        attempted = attempted,
    )

    private fun persist() {
        val cart = _state.value.cart
        val snapshot = draft(cart, attempted = false)
        viewModelScope.launch {
            if (cart.isEmpty()) drafts.delete(userId, sessionId) else drafts.save(snapshot)
        }
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
        private val drafts: DraftRepository,
        private val contact: ContactTracker,
        private val branchId: String,
        private val userId: String,
        private val sessionId: String,
        private val tableNumber: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OrderViewModel(menu, orders, drafts, contact, branchId, userId, sessionId, tableNumber) as T
    }

    private companion object {
        const val PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE"
    }
}
