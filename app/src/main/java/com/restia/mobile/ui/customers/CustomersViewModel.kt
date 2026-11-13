package com.restia.mobile.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.CustomerCreateRequest
import com.restia.mobile.core.network.CustomerDto
import com.restia.mobile.core.network.CustomerSearchRequest
import com.restia.mobile.core.network.CustomerUpdateRequest
import com.restia.mobile.data.customers.CustomersRepository
import com.restia.mobile.ui.reservations.isValidPhone
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Preferencias como se escriben: separadas por coma, sin vacías ni repetidas, hasta 50 de 60 caracteres. */
fun parsePreferences(text: String): List<String> =
    text.split(',').map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinct().take(50)

/** Un teléfono completo busca por teléfono; cualquier otro texto de 2 letras o más, por nombre. */
fun searchRequest(query: String): CustomerSearchRequest? {
    val q = query.trim()
    val digits = q.filter { it.isDigit() || it == '+' }
    return when {
        q.startsWith("+") && isValidPhone(digits) -> CustomerSearchRequest(phone = digits)
        q.length >= 2 -> CustomerSearchRequest(name = q)
        else -> null
    }
}

/** Formulario de alta o edición. En edición solo se manda lo que cambió. */
data class CustomerForm(
    val original: CustomerDto? = null,
    val name: String = "",
    val phone: String = "+52",
    val email: String = "",
    val notes: String = "",
    val preferences: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    private val emailOk: Boolean get() = email.isBlank() || Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(email.trim())
    val canSubmit: Boolean get() = !submitting && name.isNotBlank() && isValidPhone(phone) && emailOk && (original == null || changes() != CustomerUpdateRequest())

    fun changes(): CustomerUpdateRequest {
        val o = original ?: return CustomerUpdateRequest()
        val prefs = parsePreferences(preferences)
        return CustomerUpdateRequest(
            name = name.trim().takeIf { it != o.name },
            phone = phone.trim().takeIf { it != o.phone },
            email = email.trim().takeIf { it.isNotEmpty() && it != o.email },
            // El contrato no admite notas vacías: borrarlas del todo queda pendiente en el backend.
            notes = notes.trim().takeIf { it.isNotEmpty() && it != (o.profile.notes ?: "") },
            preferences = prefs.takeIf { it != o.profile.preferences },
        )
    }

    companion object {
        fun of(c: CustomerDto) = CustomerForm(
            original = c,
            name = c.name,
            phone = c.phone ?: "",
            email = c.email ?: "",
            notes = c.profile.notes ?: "",
            preferences = c.profile.preferences.joinToString(", "),
        )
    }
}

data class CustomersUiState(
    val query: String = "",
    val results: List<CustomerDto> = emptyList(),
    val searched: Boolean = false,
    val searching: Boolean = false,
    val failure: ApiFailure? = null,
    /** Cliente abierto. */
    val selected: CustomerDto? = null,
    val loadingSelected: Boolean = false,
    val form: CustomerForm? = null,
    val saved: Boolean = false,
)

class CustomersViewModel(
    private val repository: CustomersRepository,
    private val branchId: String,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(CustomersUiState())
    val state: StateFlow<CustomersUiState> = _state

    private var searchJob: Job? = null
    private var createIntent: CustomerCreateRequest? = null
    private var createKey = ""

    fun setQuery(text: String) {
        _state.value = _state.value.copy(query = text.take(120))
    }

    fun search() {
        val body = searchRequest(_state.value.query) ?: return
        searchJob?.cancel()
        _state.value = _state.value.copy(searching = true, failure = null)
        searchJob = viewModelScope.launch {
            _state.value = when (val r = repository.search(branchId, body)) {
                is ApiResult.Success -> _state.value.copy(results = r.value, searched = true, searching = false)
                is ApiResult.Failure -> _state.value.copy(searching = false, failure = r.failure)
            }
        }
    }

    /** Abre un cliente por id, por ejemplo desde una reserva. */
    fun open(id: String) {
        _state.value = _state.value.copy(loadingSelected = true, failure = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.get(branchId, id)) {
                is ApiResult.Success -> _state.value.copy(selected = r.value, loadingSelected = false)
                is ApiResult.Failure -> _state.value.copy(loadingSelected = false, failure = r.failure)
            }
        }
    }

    fun select(c: CustomerDto) {
        _state.value = _state.value.copy(selected = c)
    }

    fun closeSelected() {
        _state.value = _state.value.copy(selected = null)
    }

    fun startCreate() {
        // Si se buscó un teléfono, se propone para el alta.
        val phone = searchRequest(_state.value.query)?.phone ?: "+52"
        val name = _state.value.query.trim().takeIf { searchRequest(it)?.name != null } ?: ""
        _state.value = _state.value.copy(form = CustomerForm(name = name, phone = phone), saved = false)
    }

    fun startEdit() {
        val c = _state.value.selected ?: return
        _state.value = _state.value.copy(form = CustomerForm.of(c), saved = false)
    }

    fun editForm(change: (CustomerForm) -> CustomerForm) {
        val f = _state.value.form ?: return
        if (!f.submitting) _state.value = _state.value.copy(form = change(f).copy(failure = null))
    }

    fun setName(v: String) = editForm { it.copy(name = v.take(120)) }
    fun setPhone(v: String) = editForm { it.copy(phone = v.filter { c -> c.isDigit() || c == '+' }.take(16)) }
    fun setEmail(v: String) = editForm { it.copy(email = v.take(254)) }
    fun setNotes(v: String) = editForm { it.copy(notes = v.take(500)) }
    fun setPreferences(v: String) = editForm { it.copy(preferences = v.take(3000)) }

    fun dismissForm() {
        if (_state.value.form?.submitting != true) _state.value = _state.value.copy(form = null)
    }

    fun submit() {
        val f = _state.value.form ?: return
        if (!f.canSubmit) return
        _state.value = _state.value.copy(form = f.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val original = f.original
            val result = if (original == null) {
                val body = CustomerCreateRequest(
                    name = f.name.trim(),
                    phone = f.phone.trim(),
                    email = f.email.trim().ifEmpty { null },
                    notes = f.notes.trim().ifEmpty { null },
                    preferences = parsePreferences(f.preferences).ifEmpty { null },
                )
                // Mismo alta, misma clave: reintentar tras perder la respuesta no duplica al cliente.
                if (createIntent != body) {
                    createIntent = body
                    createKey = newKey()
                }
                repository.create(branchId, createKey, body)
            } else {
                // PATCH con If-Match: repetirlo con la versión vieja responde 409, no se aplica dos veces.
                repository.update(branchId, original, f.changes())
            }
            _state.value = when (result) {
                is ApiResult.Success -> {
                    createIntent = null
                    val updated = _state.value.results.map { if (it.id == result.value.id) result.value else it }
                    _state.value.copy(form = null, selected = result.value, saved = true, results = updated)
                }
                is ApiResult.Failure -> _state.value.copy(form = _state.value.form?.copy(submitting = false, failure = result.failure))
            }
        }
    }

    class Factory(private val repository: CustomersRepository, private val branchId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CustomersViewModel(repository, branchId) as T
    }
}
