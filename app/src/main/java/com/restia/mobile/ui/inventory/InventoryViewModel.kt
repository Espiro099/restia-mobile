package com.restia.mobile.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.IngredientDto
import com.restia.mobile.data.inventory.InventoryRepository
import com.restia.mobile.data.inventory.isLow
import com.restia.mobile.data.inventory.normalizeCost
import com.restia.mobile.data.inventory.normalizeQuantity
import java.text.Normalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class InventoryFilter { ALL, LOW, BLOCKED }

/** Lo que cocina puede hacer con un insumo, según sus permisos. */
enum class InventoryAction { WASTE, RECEIVE, COUNT, BLOCK }

/** Permisos de inventario de quien usa la pantalla. */
data class InventoryPermissions(val waste: Boolean, val receive: Boolean, val count: Boolean, val block: Boolean) {
    fun actions(ingredient: IngredientDto): List<InventoryAction> = buildList {
        if (receive) add(InventoryAction.RECEIVE)
        if (waste) add(InventoryAction.WASTE)
        if (count) add(InventoryAction.COUNT)
        if (block && ingredient.foodSafetyBlock == null) add(InventoryAction.BLOCK)
    }
}

/** Datos del formulario; cada acción usa los suyos. */
data class InventoryForm(val quantity: String = "", val reason: String = "", val unitCost: String = "", val supplier: String = "")

/** Por qué no se puede guardar el formulario, o null si está completo. */
enum class FormProblem { QUANTITY, REASON, COST, SUPPLIER }

fun formProblem(action: InventoryAction, form: InventoryForm): FormProblem? = when (action) {
    InventoryAction.WASTE -> when {
        normalizeQuantity(form.quantity) == null -> FormProblem.QUANTITY
        form.reason.trim().length < 3 -> FormProblem.REASON
        else -> null
    }
    InventoryAction.RECEIVE -> when {
        normalizeQuantity(form.quantity) == null -> FormProblem.QUANTITY
        normalizeCost(form.unitCost) == null -> FormProblem.COST
        form.supplier.isBlank() -> FormProblem.SUPPLIER
        else -> null
    }
    // Contar cero es válido: no quedó nada.
    InventoryAction.COUNT -> if (form.quantity.trim().replace(',', '.').let { it == "0" || normalizeQuantity(it) != null }) null else FormProblem.QUANTITY
    InventoryAction.BLOCK -> if (form.reason.trim().length < 3) FormProblem.REASON else null
}

private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Primero lo bloqueado y lo que está bajo mínimo; luego por nombre. */
fun visibleIngredients(all: List<IngredientDto>, filter: InventoryFilter, query: String): List<IngredientDto> {
    val q = fold(query.trim())
    return all
        .filter { filter == InventoryFilter.ALL || (filter == InventoryFilter.LOW && it.isLow()) || (filter == InventoryFilter.BLOCKED && it.foodSafetyBlock != null) }
        .filter { q.isEmpty() || fold(it.name).contains(q) || fold(it.code).contains(q) }
        .sortedWith(compareBy({ it.foodSafetyBlock == null }, { !it.isLow() }, { fold(it.name) }))
}

data class InventoryUiState(
    val ingredients: List<IngredientDto> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val failure: ApiFailure? = null,
    val filter: InventoryFilter = InventoryFilter.ALL,
    val query: String = "",
    /** Insumo elegido y, si ya se escogió, la acción en captura. */
    val selected: IngredientDto? = null,
    val action: InventoryAction? = null,
    val form: InventoryForm = InventoryForm(),
    val saving: Boolean = false,
    val actionFailure: ApiFailure? = null,
    /** Lo último que se guardó, para confirmarlo en pantalla. */
    val done: Pair<InventoryAction, String>? = null,
) {
    val visible: List<IngredientDto> get() = visibleIngredients(ingredients, filter, query)
    val low: Int get() = ingredients.count { it.isLow() }
    val blocked: Int get() = ingredients.count { it.foodSafetyBlock != null }
}

/**
 * Inventario desde cocina: ve el stock contra el mínimo y registra merma, entradas de mercancía, conteos y
 * bloqueos por inocuidad. El stock se recalcula en el servidor unos segundos después de cada movimiento, así
 * que la lista se vuelve a pedir tras guardar.
 */
class InventoryViewModel(
    private val repo: InventoryRepository,
    private val branchId: String,
    /** Espera antes de recargar tras un movimiento; 0 en pruebas. */
    private val settleMillis: Long = 4_000,
) : ViewModel() {
    private val _state = MutableStateFlow(InventoryUiState())
    val state: StateFlow<InventoryUiState> = _state

    fun load() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            _state.value = when (val r = repo.ingredients(branchId)) {
                is ApiResult.Success -> _state.value.copy(ingredients = r.value, loaded = true, refreshing = false, failure = null)
                is ApiResult.Failure -> _state.value.copy(refreshing = false, failure = r.failure)
            }
        }
    }

    fun setFilter(filter: InventoryFilter) { _state.value = _state.value.copy(filter = filter) }

    fun setQuery(query: String) { _state.value = _state.value.copy(query = query.take(60)) }

    fun select(ingredient: IngredientDto?) {
        _state.value = _state.value.copy(selected = ingredient, action = null, form = InventoryForm(), actionFailure = null)
    }

    fun start(action: InventoryAction) {
        _state.value = _state.value.copy(action = action, form = InventoryForm(), actionFailure = null)
    }

    fun edit(form: InventoryForm) { _state.value = _state.value.copy(form = form, actionFailure = null) }

    fun cancel() {
        if (!_state.value.saving) _state.value = _state.value.copy(selected = null, action = null, form = InventoryForm(), actionFailure = null)
    }

    fun clearDone() { _state.value = _state.value.copy(done = null) }

    fun save() {
        val s = _state.value
        val ingredient = s.selected ?: return
        val action = s.action ?: return
        if (s.saving || formProblem(action, s.form) != null) return
        _state.value = s.copy(saving = true, actionFailure = null)
        val f = s.form
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                InventoryAction.WASTE -> repo.waste(branchId, ingredient.id, normalizeQuantity(f.quantity)!!, f.reason)
                InventoryAction.RECEIVE -> repo.receive(branchId, ingredient.id, normalizeQuantity(f.quantity)!!, normalizeCost(f.unitCost)!!, f.supplier)
                InventoryAction.COUNT -> repo.count(branchId, ingredient.id, normalizeQuantity(f.quantity) ?: "0")
                InventoryAction.BLOCK -> repo.blockFoodSafety(branchId, ingredient.id, f.reason)
            }
            when (r) {
                is ApiResult.Failure -> _state.value = _state.value.copy(saving = false, actionFailure = r.failure)
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(saving = false, selected = null, action = null, form = InventoryForm(), done = action to ingredient.name)
                    // El conteo no mueve el stock hasta que el gerente lo aplica; el bloqueo se ve al momento.
                    if (action == InventoryAction.WASTE || action == InventoryAction.RECEIVE) delay(settleMillis)
                    load()
                }
            }
        }
    }

    class Factory(private val repo: InventoryRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = InventoryViewModel(repo, branchId) as T
    }
}
