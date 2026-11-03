package com.restia.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FormState(val busy: Boolean = false, val failure: ApiFailure? = null)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    val authState = repository.state
    val notice = repository.notice

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form

    init {
        start()
    }

    fun start() {
        viewModelScope.launch { repository.start() }
    }

    fun enroll(code: String, displayName: String) = submit { repository.enroll(code.trim(), displayName.trim()) }

    fun login(email: String, password: String) = submit { repository.login(email.trim(), password) }

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
