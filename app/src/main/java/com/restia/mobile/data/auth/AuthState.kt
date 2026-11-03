package com.restia.mobile.data.auth

import com.restia.mobile.core.network.MeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState
    data object NeedsEnrollment : AuthState
    data object NeedsLogin : AuthState
    data object Offline : AuthState
    data class Authenticated(val me: MeData, val branchId: String) : AuthState
}

enum class SessionNotice { SESSION_EXPIRED, DEVICE_BLOCKED }

class AuthStateHolder {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    private val _notice = MutableStateFlow<SessionNotice?>(null)
    val notice: StateFlow<SessionNotice?> = _notice

    fun set(state: AuthState) {
        _state.value = state
    }

    fun notify(notice: SessionNotice?) {
        _notice.value = notice
    }
}
