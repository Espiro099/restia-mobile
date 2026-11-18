package com.restia.mobile.ui.kitchen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MenuProductDto
import com.restia.mobile.data.orders.MenuRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AvailabilityUiState(
    val products: List<MenuProductDto> = emptyList(),
    val loaded: Boolean = false,
    /** La carta viene de caché porque no se pudo consultar. */
    val stale: Boolean = false,
    val failure: ApiFailure? = null,
    /** Platillos con un cambio en curso: su interruptor queda deshabilitado. */
    val saving: Set<String> = emptySet(),
    /** Último cambio que no se pudo aplicar, con el nombre del platillo. */
    val actionFailure: Pair<String, ApiFailure>? = null,
) {
    val soldOut: Int get() = products.count { it.availability != "AVAILABLE" }
}

/** Primero lo agotado (es lo que cocina vuelve a habilitar), luego por categoría y nombre. */
fun availabilityOrder(products: List<MenuProductDto>): List<MenuProductDto> =
    products.sortedWith(compareBy({ it.availability == "AVAILABLE" }, { it.category }, { it.displayOrder }, { it.name }))

/**
 * Agotados desde cocina: la carta con un interruptor por platillo. Cada cambio se aplica al momento en la
 * lista; si el servidor lo rechaza, vuelve a como estaba y se explica.
 */
class AvailabilityViewModel(private val menu: MenuRepository, private val branchId: String) : ViewModel() {
    private val _state = MutableStateFlow(AvailabilityUiState())
    val state: StateFlow<AvailabilityUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = when (val r = menu.load(branchId)) {
                is ApiResult.Success -> _state.value.copy(products = availabilityOrder(r.value.menu.products), loaded = true, stale = r.value.stale, failure = null)
                is ApiResult.Failure -> _state.value.copy(failure = r.failure)
            }
        }
    }

    fun toggle(product: MenuProductDto) {
        if (product.id in _state.value.saving) return
        val available = product.availability != "AVAILABLE"
        val value = if (available) "AVAILABLE" else "SOLD_OUT"
        set(product.id, value)
        _state.value = _state.value.copy(saving = _state.value.saving + product.id, actionFailure = null)
        viewModelScope.launch {
            val r = menu.setAvailability(branchId, product.id, available)
            if (r is ApiResult.Failure) set(product.id, product.availability)
            _state.value = _state.value.copy(
                saving = _state.value.saving - product.id,
                actionFailure = (r as? ApiResult.Failure)?.let { product.name to it.failure } ?: _state.value.actionFailure,
            )
        }
    }

    /** Cambia el valor en la lista sin reordenarla, para que la fila no salte bajo el dedo. */
    private fun set(productId: String, value: String) {
        _state.value = _state.value.copy(products = _state.value.products.map { if (it.id == productId) it.copy(availability = value) else it })
    }

    class Factory(private val menu: MenuRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AvailabilityViewModel(menu, branchId) as T
    }
}
