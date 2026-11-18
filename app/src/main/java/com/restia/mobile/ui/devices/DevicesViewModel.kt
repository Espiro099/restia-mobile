package com.restia.mobile.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.DeviceDto
import com.restia.mobile.core.network.EnrollmentCodeDto
import com.restia.mobile.data.devices.DeviceClosing
import com.restia.mobile.data.devices.DevicesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Qué puede hacer quien entra: ver basta para la lista; el código y cerrar llevan su permiso. */
data class DevicePermissions(val enroll: Boolean, val manage: Boolean)

/** Bloquear o retirar un dispositivo, con el motivo que pide la API (3 a 500 caracteres). */
data class ClosingState(
    val device: DeviceDto,
    val how: DeviceClosing,
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && reason.trim().length in REASON_MIN..REASON_MAX

    companion object {
        const val REASON_MIN = 3
        const val REASON_MAX = 500
    }
}

data class DevicesUiState(
    val devices: List<DeviceDto> = emptyList(),
    /** Id de este teléfono en la lista; null si la sesión no es de un dispositivo dado de alta. */
    val thisDevice: String? = null,
    val loaded: Boolean = false,
    val failure: ApiFailure? = null,
    /** Código recién generado; se olvida al cerrar el diálogo. */
    val code: EnrollmentCodeDto? = null,
    val generating: Boolean = false,
    val codeFailure: ApiFailure? = null,
    val closing: ClosingState? = null,
    /** Último cierre hecho, para el aviso bajo el encabezado. */
    val closed: DeviceClosing? = null,
)

/** Se bloquea o retira solo uno activo, y nunca este teléfono: se quedaría fuera quien lo hace. */
fun canClose(device: DeviceDto, thisDevice: String?): Boolean =
    device.status == DevicesRepository.ACTIVE && !device.id.equals(thisDevice, ignoreCase = true)

/** "ABCD EFGH JKMN": en grupos de 4 para dictarlo; la API ignora espacios y guiones. */
fun groupCode(code: String): String = code.chunked(4).joinToString(" ")

class DevicesViewModel(
    private val repository: DevicesRepository,
    private val branchId: String,
    val permissions: DevicePermissions,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.value = when (val r = repository.all(branchId)) {
                is ApiResult.Success -> _state.value.copy(devices = r.value.devices, thisDevice = r.value.thisDevice, loaded = true, failure = null)
                is ApiResult.Failure -> _state.value.copy(failure = r.failure)
            }
        }
    }

    fun generateCode() {
        if (!permissions.enroll || _state.value.generating) return
        _state.value = _state.value.copy(generating = true, codeFailure = null, closed = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.enrollmentCode(branchId)) {
                is ApiResult.Success -> _state.value.copy(generating = false, code = r.value)
                is ApiResult.Failure -> _state.value.copy(generating = false, codeFailure = r.failure)
            }
        }
    }

    fun dismissCode() {
        _state.value = _state.value.copy(code = null, codeFailure = null)
    }

    fun startClosing(device: DeviceDto, how: DeviceClosing) {
        if (!permissions.manage || !canClose(device, _state.value.thisDevice) || _state.value.closing != null) return
        _state.value = _state.value.copy(closing = ClosingState(device, how), closed = null)
    }

    fun setReason(reason: String) {
        val c = _state.value.closing ?: return
        if (!c.submitting) _state.value = _state.value.copy(closing = c.copy(reason = reason.take(ClosingState.REASON_MAX), failure = null))
    }

    fun dismissClosing() {
        if (_state.value.closing?.submitting != true) _state.value = _state.value.copy(closing = null)
    }

    fun confirmClosing() {
        val c = _state.value.closing ?: return
        if (!c.canSubmit) return
        _state.value = _state.value.copy(closing = c.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = repository.close(branchId, c.device.id, c.how, c.reason)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(closing = null, closed = c.how)
                    // Se vuelve a pedir la lista: si ya estaba cerrado, el estado real es el del servidor.
                    load()
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(closing = c.copy(submitting = false, failure = r.failure))
            }
        }
    }

    class Factory(
        private val repository: DevicesRepository,
        private val branchId: String,
        private val permissions: DevicePermissions,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DevicesViewModel(repository, branchId, permissions) as T
    }
}
