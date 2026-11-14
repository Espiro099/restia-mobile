package com.restia.mobile.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.data.account.PasswordRepository
import com.restia.mobile.ui.members.isValidEmail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val MIN_PASSWORD = 12
const val MAX_PASSWORD = 128

/** Lo que se revisa antes de enviar; el backend además rechaza contraseñas filtradas. */
enum class PasswordProblem { TOO_SHORT, MISMATCH, SAME_AS_CURRENT }

fun passwordProblem(current: String, new: String, confirm: String): PasswordProblem? = when {
    new.length < MIN_PASSWORD -> PasswordProblem.TOO_SHORT
    new != confirm -> PasswordProblem.MISMATCH
    new == current -> PasswordProblem.SAME_AS_CURRENT
    else -> null
}

/** Las contraseñas viven solo aquí, en memoria; nunca en el estado guardado. */
data class ChangePasswordState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
    val done: Boolean = false,
) {
    val problem: PasswordProblem? get() = passwordProblem(current, new, confirm)
    val canSubmit: Boolean get() = !submitting && current.isNotEmpty() && problem == null

    override fun toString(): String = "ChangePasswordState(submitting=$submitting, done=$done)"
}

class ChangePasswordViewModel(private val repository: PasswordRepository) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordState())
    val state: StateFlow<ChangePasswordState> = _state

    fun edit(transform: (ChangePasswordState) -> ChangePasswordState) {
        if (_state.value.submitting || _state.value.done) return
        _state.value = transform(_state.value).let {
            it.copy(current = it.current.take(MAX_PASSWORD), new = it.new.take(MAX_PASSWORD), confirm = it.confirm.take(MAX_PASSWORD), failure = null)
        }
    }

    fun reset() {
        if (!_state.value.submitting) _state.value = ChangePasswordState()
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.value = s.copy(submitting = true, failure = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.change(s.current, s.new)) {
                // Se borran las contraseñas en cuanto ya no hacen falta.
                is ApiResult.Success -> ChangePasswordState(done = true)
                is ApiResult.Failure -> _state.value.copy(submitting = false, failure = r.failure)
            }
        }
    }

    class Factory(private val repository: PasswordRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChangePasswordViewModel(repository) as T
    }
}

data class ForgotPasswordState(
    val open: Boolean = false,
    val email: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
    val sent: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting && isValidEmail(email)
}

/** "Olvidé mi contraseña" desde el inicio de sesión: el enlace llega por correo y se usa en la página web. */
class ForgotPasswordViewModel(private val repository: PasswordRepository) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordState())
    val state: StateFlow<ForgotPasswordState> = _state

    fun open(email: String) {
        _state.value = ForgotPasswordState(open = true, email = email.trim().take(254))
    }

    fun setEmail(text: String) {
        if (_state.value.submitting) return
        _state.value = _state.value.copy(email = text.take(254), failure = null)
    }

    fun dismiss() {
        if (_state.value.submitting) return
        _state.value = ForgotPasswordState()
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.value = s.copy(submitting = true, failure = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.forgot(s.email.trim())) {
                is ApiResult.Success -> _state.value.copy(submitting = false, sent = true)
                is ApiResult.Failure -> _state.value.copy(submitting = false, failure = r.failure)
            }
        }
    }

    class Factory(private val repository: PasswordRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ForgotPasswordViewModel(repository) as T
    }
}
