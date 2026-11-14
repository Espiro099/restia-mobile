package com.restia.mobile.data.members

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.MemberCreateRequest
import com.restia.mobile.core.network.MemberCreatedDto
import com.restia.mobile.core.network.MemberDto
import com.restia.mobile.core.network.MemberRoleRequest
import com.restia.mobile.core.network.PasswordSetupLinkDto
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

class MembersRepository(private val api: SessionApi) {
    /** Una página de 100 alcanza para el personal de una sucursal. */
    suspend fun list(branchId: String): ApiResult<List<MemberDto>> = apiCall { api.listMembers(branchId, 1, PER_PAGE).data }

    suspend fun create(branchId: String, body: MemberCreateRequest): ApiResult<MemberCreatedDto> =
        apiCall { api.createMember(branchId, body).data }

    suspend fun changeRole(branchId: String, member: MemberDto, role: String): ApiResult<MemberDto> =
        apiCall { api.changeMemberRole(branchId, member.user.id, "\"${member.version}\"", MemberRoleRequest(role)).data }

    suspend fun revoke(branchId: String, member: MemberDto, reason: String): ApiResult<MemberDto> =
        apiCall { api.revokeMember(branchId, member.user.id, "\"${member.version}\"", ReasonRequest(reason)).data }

    suspend fun setupLink(userId: String): ApiResult<PasswordSetupLinkDto> = apiCall { api.passwordSetupLink(userId).data }

    private companion object {
        const val PER_PAGE = 100
    }
}
