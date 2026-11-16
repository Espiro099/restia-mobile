package com.restia.mobile.ui.checks

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.CheckUnitDto

/** Lo cancelado o anulado no se cobra; lo demás sí (con o sin cortesía). */
fun billedUnits(check: CheckDto): List<CheckUnitDto> = check.units.filter { it.status != "CANCELLED" && it.status != "VOIDED" }

/** Pagos confirmados vigentes o en curso: congelan la cuenta (C-2). */
fun hasPayments(check: CheckDto): Boolean = amountOf(check.paidTotal).signum() != 0 || amountOf(check.pendingTotal).signum() != 0

/** C-5: el origen puede estar OPEN, REQUESTED o REFUNDED; con pagos vigentes no se mueve nada. */
fun canMoveFrom(check: CheckDto): Boolean =
    billedUnits(check).isNotEmpty() && when (check.status) {
        "REFUNDED" -> true
        "OPEN", "REQUESTED" -> !hasPayments(check)
        else -> false
    }

/** Destinos posibles: otra cuenta OPEN de la misma sesión. */
fun moveTargets(checks: List<CheckDto>, source: CheckDto): List<CheckDto> = checks.filter { it.id != source.id && it.status == "OPEN" }

fun canReopen(check: CheckDto): Boolean = check.status == "REQUESTED" && !hasPayments(check)

fun canCancelCheck(check: CheckDto): Boolean = check.status in setOf("OPEN", "REQUESTED") && billedUnits(check).isEmpty() && !hasPayments(check)

/** Dar o quitar una cortesía: cuenta OPEN o REQUESTED, sin pagos. */
fun canAdjust(check: CheckDto): Boolean = check.status in setOf("OPEN", "REQUESTED") && !hasPayments(check)

private fun validReason(text: String) = text.trim().length >= 3

/** Datos del gerente que autoriza en este dispositivo (D-37). La contraseña nunca se registra. */
data class Approver(val email: String = "", val password: String = "") {
    val complete: Boolean get() = email.trim().contains('@') && password.isNotEmpty()
    override fun toString(): String = "Approver(***)"
}

/** Token de un gerente: sirve una vez, para estos recursos y este motivo, hasta que vence. */
data class GrantedApproval(val token: String, val resourceIds: List<String>, val reason: String, val validUntil: Long) {
    override fun toString(): String = "GrantedApproval(resources=$resourceIds)"
}

enum class CheckActionKind(val approvalAction: String?) {
    REOPEN("CHECK_REOPEN"),
    CANCEL(null),
    COURTESY("CHECK_ADJUSTMENT"),
    REVERT("CHECK_ADJUSTMENT"),
}

/** Reabrir, cancelar, dar o quitar una cortesía: todas con motivo; algunas, con autorización. */
data class CheckAction(
    val kind: CheckActionKind,
    val check: CheckDto,
    val unit: CheckUnitDto? = null,
    /** Sin el permiso directo, un gerente autoriza en este dispositivo. */
    val needsApproval: Boolean = false,
    val reason: String = "",
    val approver: Approver = Approver(),
    val approval: GrantedApproval? = null,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    /** A qué queda ligado el token: la cuenta, la unidad o la cortesía. */
    val resourceIds: List<String>
        get() = when (kind) {
            CheckActionKind.REOPEN, CheckActionKind.CANCEL -> listOf(check.id)
            CheckActionKind.COURTESY -> listOfNotNull(unit?.unitId)
            CheckActionKind.REVERT -> listOfNotNull(unit?.adjustment?.id)
        }

    /** Hay token para el motivo escrito ahora; si cambia, ya no sirve. */
    val approved: Boolean get() = approval != null && approval.reason == reason.trim() && approval.resourceIds == resourceIds
    val showsApprover: Boolean get() = needsApproval && !approved
    val canSubmit: Boolean
        get() = !submitting && validReason(reason) && (!showsApprover || approver.complete) && (kind != CheckActionKind.COURTESY || unit != null)

    override fun toString(): String = "CheckAction(kind=$kind, check=${check.id}, needsApproval=$needsApproval, approved=$approved)"
}

/**
 * Mover unidades a otra cuenta de la sesión, o a una cuenta nueva (dividir). La cuenta nueva se crea una
 * sola vez: si mover falla, el reintento usa la que ya se creó.
 */
data class MoveUnitsState(
    val source: CheckDto,
    val selected: Set<String> = emptySet(),
    /** null: cuenta nueva. */
    val targetId: String? = null,
    val label: String = "",
    val created: CheckDto? = null,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
) {
    val canSubmit: Boolean get() = !submitting && selected.isNotEmpty()
}

/** Unidades a las que se puede dar cortesía: se cobran y todavía no tienen. */
fun courtesyCandidates(check: CheckDto): List<CheckUnitDto> = billedUnits(check).filter { it.adjustment == null }
