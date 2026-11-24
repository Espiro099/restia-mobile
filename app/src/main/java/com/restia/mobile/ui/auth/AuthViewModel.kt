package com.restia.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.data.auth.AuthRepository
import com.restia.mobile.data.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** `google`: el intento fue con Google, así que un 401 no habla de contraseñas. `problem`: aviso local (string). */
data class FormState(val busy: Boolean = false, val failure: ApiFailure? = null, val google: Boolean = false, val problem: Int? = null)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    val authState = repository.state
    val notice = repository.notice

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form

    private val _googleClientId = MutableStateFlow<String?>(null)
    /** Con valor, la pantalla de acceso muestra «Continuar con Google». */
    val googleClientId: StateFlow<String?> = _googleClientId

    init {
        start()
        viewModelScope.launch {
            authState.collect { if (it == AuthState.NeedsLogin && _googleClientId.value == null) _googleClientId.value = repository.googleClientId() }
        }
    }

    fun start() {
        viewModelScope.launch { repository.start() }
    }

    fun enroll(code: String, displayName: String) = submit { repository.enroll(code.trim(), displayName.trim()) }

    fun login(email: String, password: String) = submit { repository.login(email.trim(), password) }

    fun loginWithGoogle(idToken: String) {
        if (_form.value.busy) return
        _form.value = FormState(busy = true, google = true)
        viewModelScope.launch { _form.value = FormState(failure = repository.loginWithGoogle(idToken), google = true) }
    }

    /** Credential Manager no dio un token: sin cuenta de Google en el teléfono o un error de Google. */
    fun googleProblem(message: Int) {
        if (!_form.value.busy) _form.value = FormState(problem = message, google = true)
    }

    fun logout() = submit {
        repository.logout()
        null
    }

    private fun submit(action: suspend () -> ApiFailure?) {
        if (_form.value.busy) return
        _form.value = FormState(busy = true)
        viewModelScope.launch { _form.value = FormState(failure = action()) }
    }

    class Factory(private val repository: AuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(repository) as T
    }
}
