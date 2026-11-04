package com.restia.mobile.data.orders

import com.restia.mobile.core.network.ApiJson
import com.restia.mobile.core.network.OrderItemRequest
import com.restia.mobile.core.storage.BlobStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * Comanda en captura, ligada al usuario que la inició (D-36, 5.7).
 * [attempted] se guarda antes de enviar: al reabrir, un envío interrumpido queda en duda.
 */
@Serializable
data class OrderDraft(
    val userId: String,
    val branchId: String,
    val sessionId: String,
    val tableNumber: Int,
    val capturedAt: String,
    val lines: List<CartLine>,
    val clientOrderUuid: String? = null,
    val idempotencyKey: String? = null,
    val requestItems: List<OrderItemRequest>? = null,
    val attempted: Boolean = false,
)

class DraftRepository(
    private val blobs: BlobStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    private fun name(userId: String, sessionId: String) = "$PREFIX${userId}_$sessionId"

    suspend fun get(userId: String, sessionId: String): OrderDraft? = locked {
        decode(blobs.read(name(userId, sessionId)))?.takeIf { it.userId == userId }
    }

    suspend fun save(draft: OrderDraft) = locked {
        blobs.write(name(draft.userId, draft.sessionId), ApiJson.encodeToString(OrderDraft.serializer(), draft))
    }

    suspend fun delete(userId: String, sessionId: String) = locked { blobs.delete(name(userId, sessionId)) }

    /** Solo los borradores del usuario: otro empleado no los puede ver ni enviar. */
    suspend fun listFor(userId: String, branchId: String): List<OrderDraft> = locked {
        blobs.names(PREFIX)
            .mapNotNull { decode(blobs.read(it)) }
            .filter { it.userId == userId && it.branchId == branchId && it.lines.isNotEmpty() }
            .sortedBy { it.tableNumber }
    }

    private fun decode(json: String?): OrderDraft? = json?.let {
        try {
            ApiJson.decodeFromString(OrderDraft.serializer(), it)
        } catch (_: SerializationException) {
            null
        }
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(io) { block() } }

    private companion object {
        const val PREFIX = "draft-"
    }
}
