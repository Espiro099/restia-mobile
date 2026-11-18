package com.restia.mobile.data.notifications

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.NotificationDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

/** Los avisos más recientes y cuántos siguen sin leer. */
data class NotificationPage(val items: List<NotificationDto>, val unread: Int)

class NotificationsRepository(private val api: SessionApi) {
    /** Una página basta: la campana muestra lo reciente y el servidor borra lo de más de 30 días. */
    suspend fun list(branchId: String): ApiResult<NotificationPage> = apiCall {
        val page = api.listNotifications(branchId, PER_PAGE)
        NotificationPage(page.data, page.meta.unreadCount)
    }

    suspend fun read(branchId: String, id: String): ApiResult<NotificationDto> = apiCall { api.readNotification(branchId, id).data }

    suspend fun readAll(branchId: String): ApiResult<Int> = apiCall { api.readAllNotifications(branchId).data.updated }

    private companion object {
        const val PER_PAGE = 50
    }
}
