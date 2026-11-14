package com.restia.mobile.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MemberCreateRequest
import com.restia.mobile.core.network.MemberDto
import com.restia.mobile.data.members.MembersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val ROLES = listOf("ADMIN", "MANAGER", "WAITER", "KITCHEN")

/** Un ADMIN maneja todos los roles; un gerente, solo meseros y cocina (igual que el backend). */
fun manageableRoles(actorRole: String): List<String> = when (actorRole) {
    "ADMIN" -> ROLES
    "MANAGER" -> listOf("WAITER", "KITCHEN")
    else -> emptyList()
}

/** Nadie se cambia a sí mismo; los dados de baja se vuelven a dar de alta con "Agregar". */
fun canManage(actorRole: String, actorUserId: String, member: MemberDto): Boolean =
    member.user.id != actorUserId && member.active && member.role in manageableRoles(actorRole)

fun isValidEmail(email: String): Boolean = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(email.trim())

/** Activos primero, luego por nombre. */
fun sortMembers(members: List<MemberDto>): List<MemberDto> =
    members.sortedWith(compareBy<MemberDto> { !it.active }.thenBy { it.user.name.lowercase() })

data class AddMemberForm(
    val name: String = "",
    val email: String = "",
    val role: String = "WAITER",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && name.isNotBlank() && isValidEmail(email)
}

/**
 * Resultado de un alta o de pedir un enlace nuevo. El enlace es una credencial: vive solo en memoria y
 * se muestra una vez. Sin enlace, la persona ya tenía cuenta en Restia.
 */
data class SetupLink(val name: String, val url: String?, val expiresAt: String?)

data class MemberAction(
    val member: MemberDto,
    val role: String = member.role,
    val revoking: Boolean = false,
    val reason: String = "",
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
)

data class MembersUiState(
    val members: List<MemberDto> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: ApiFailure? = null,
    val showRevoked: Boolean = false,
    val adding: AddMemberForm? = null,
    val selected: MemberAction? = null,
    val link: SetupLink? = null,
    val linkFailure: ApiFailure? = null,
    val requestingLink: Boolean = false,
) {
    val visible: List<MemberDto> get() = if (showRevoked) members else members.filter { it.active }
    val revokedCount: Int get() = members.count { !it.active }
}

class MembersViewModel(
    private val repository: MembersRepository,
    private val branchId: String,
    val actorUserId: String,
    val actorRole: String,
) : ViewModel() {
    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = repository.list(branchId)) {
                is ApiResult.Success -> {
                    // El empleado abierto toma la versión vigente para que el siguiente intento no choque.
                    val selected = _state.value.selected?.let { s -> r.value.find { it.user.id == s.member.user.id }?.let { s.copy(member = it) } ?: s }
                    _state.value.copy(members = sortMembers(r.value), loaded = true, loading = false, failure = null, selected = selected)
                }
                is ApiResult.Failure -> _state.value.copy(loading = false, failure = r.failure)
            }
        }
    }

    fun toggleRevoked() {
        _state.value = _state.value.copy(showRevoked = !_state.value.showRevoked)
    }

    // --- Alta ---

    fun startAdd() {
        val roles = manageableRoles(actorRole)
        if (roles.isEmpty()) return
        _state.value = _state.value.copy(adding = AddMemberForm(role = if ("WAITER" in roles) "WAITER" else roles.first()))
    }

    fun editAdd(transform: (AddMemberForm) -> AddMemberForm) {
        val form = _state.value.adding ?: return
        if (form.submitting) return
        _state.value = _state.value.copy(adding = transform(form).copy(failure = null))
    }

    fun dismissAdd() {
        if (_state.value.adding?.submitting == true) return
        _state.value = _state.value.copy(adding = null)
    }

    fun submitAdd() {
        val form = _state.value.adding ?: return
        if (!form.canSubmit || form.role !in manageableRoles(actorRole)) return
        _state.value = _state.value.copy(adding = form.copy(submitting = true, failure = null))
        viewModelScope.launch {
            val body = MemberCreateRequest(email = form.email.trim(), name = form.name.trim(), role = form.role)
            when (val r = repository.create(branchId, body)) {
                is ApiResult.Success -> {
                    val created = r.value
                    _state.value = _state.value.copy(
                        adding = null,
                        members = sortMembers(_state.value.members.filterNot { it.user.id == created.member.user.id } + created.member),
                        link = SetupLink(created.member.user.name, created.setupUrl, created.setupExpiresAt),
                    )
                }
                is ApiResult.Failure -> _state.value = _state.value.copy(adding = form.copy(submitting = false, failure = r.failure))
            }
        }
    }

    fun dismissLink() {
        _state.value = _state.value.copy(link = null)
    }

    // --- Cambios sobre un empleado ---

    fun select(member: MemberDto) {
        _state.value = _state.value.copy(selected = MemberAction(member), linkFailure = null)
    }

    fun dismissSelected() {
        if (_state.value.selected?.submitting == true || _state.value.requestingLink) return
        _state.value = _state.value.copy(selected = null, linkFailure = null)
    }

    fun chooseRole(role: String) {
        val action = _state.value.selected ?: return
        if (action.submitting) return
        _state.value = _state.value.copy(selected = action.copy(role = role, failure = null))
    }

    fun startRevoke() {
        val action = _state.value.selected ?: return
        _state.value = _state.value.copy(selected = action.copy(revoking = true, failure = null))
    }

    fun setReason(text: String) {
        val action = _state.value.selected ?: return
        _state.value = _state.value.copy(selected = action.copy(reason = text.take(500), failure = null))
    }

    fun saveRole() {
        val action = _state.value.selected ?: return
        if (action.submitting || action.role == action.member.role || action.role !in manageableRoles(actorRole)) return
        run(action) { repository.changeRole(branchId, action.member, action.role) }
    }

    fun confirmRevoke() {
        val action = _state.value.selected ?: return
        if (action.submitting || action.reason.trim().length < 3) return
        run(action) { repository.revoke(branchId, action.member, action.reason.trim()) }
    }

    /** Solo para quien todavía no define su contraseña; el backend exige ser ADMIN de todas sus sucursales. */
    fun newSetupLink() {
        val member = _state.value.selected?.member ?: return
        if (_state.value.requestingLink) return
        _state.value = _state.value.copy(requestingLink = true, linkFailure = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.setupLink(member.user.id)) {
                is ApiResult.Success -> _state.value.copy(
                    requestingLink = false,
                    selected = null,
                    link = SetupLink(member.user.name, r.value.setupUrl, r.value.expiresAt),
                )
                is ApiResult.Failure -> _state.value.copy(requestingLink = false, linkFailure = r.failure)
            }
        }
    }

    private fun run(action: MemberAction, call: suspend () -> ApiResult<MemberDto>) {
        _state.value = _state.value.copy(selected = action.copy(submitting = true, failure = null))
        viewModelScope.launch {
            when (val r = call()) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    selected = null,
                    members = sortMembers(_state.value.members.map { if (it.user.id == r.value.user.id) r.value else it }),
                )
                is ApiResult.Failure -> {
                    _state.value = _state.value.copy(selected = action.copy(submitting = false, failure = r.failure))
                    // Otro dispositivo lo cambió antes: se recarga para mostrar lo vigente.
                    if ((r.failure as? ApiFailure.Server)?.code == "VERSION_CONFLICT") refresh()
                }
            }
        }
    }

    class Factory(
        private val repository: MembersRepository,
        private val branchId: String,
        private val actorUserId: String,
        private val actorRole: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MembersViewModel(repository, branchId, actorUserId, actorRole) as T
    }
}
