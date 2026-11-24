package com.restia.mobile.data.inventory

import com.restia.mobile.core.network.ApiFailure
import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.FoodSafetyRequest
import com.restia.mobile.core.network.IngredientDto
import com.restia.mobile.core.network.InventoryConsumptionDto
import com.restia.mobile.core.network.InventoryCountCreateRequest
import com.restia.mobile.core.network.InventoryCountDto
import com.restia.mobile.core.network.InventoryCountLineDto
import com.restia.mobile.core.network.InventoryCountLinesRequest
import com.restia.mobile.core.network.InventoryMovementRequest
import com.restia.mobile.core.network.InventoryReceiptLineRequest
import com.restia.mobile.core.network.InventoryReceiptRequest
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Cantidad como la pide la API: hasta 9 enteros y 4 decimales, punto decimal. Acepta coma. Null si no es válida o es cero. */
fun normalizeQuantity(text: String): String? {
    val clean = text.trim().replace(',', '.')
    if (!Regex("^\\d{1,9}(\\.\\d{1,4})?$").matches(clean)) return null
    val value = BigDecimal(clean)
    if (value.signum() == 0) return null
    return value.stripTrailingZeros().toPlainString()
}

/** Costo unitario en pesos: hasta 4 decimales. Acepta cero (una muestra sin costo). */
fun normalizeCost(text: String): String? {
    val clean = text.trim().replace(',', '.').removePrefix("$")
    if (!Regex("^\\d{1,12}(\\.\\d{1,4})?$").matches(clean)) return null
    val value = BigDecimal(clean)
    return (if (value.scale() < 2) value.setScale(2) else value).toPlainString()
}

/** ¿Está por debajo de su mínimo? Un mínimo de cero nunca avisa. */
fun IngredientDto.isLow(): Boolean {
    val min = minStock.toBigDecimalOrNull() ?: return false
    val now = stock.toBigDecimalOrNull() ?: return false
    return min.signum() > 0 && now < min
}

/** Lo contado reemplaza la línea del mismo insumo y conserva las demás (el PUT reemplaza todas). */
fun mergeCountLines(existing: List<InventoryCountLineDto>, line: InventoryCountLineDto): List<InventoryCountLineDto> =
    existing.filter { it.ingredientId != line.ingredientId } + line

class InventoryRepository(private val api: SessionApi, private val clock: Clock = Clock.systemUTC()) {
    /** Insumos activos de la sucursal, por código. Unos cientos a lo más: se piden todas las páginas. */
    suspend fun ingredients(branchId: String): ApiResult<List<IngredientDto>> = apiCall {
        val rows = mutableListOf<IngredientDto>()
        for (page in 1..20) {
            val r = api.listIngredients(branchId, "ACTIVE", page, PER_PAGE)
            rows += r.data
            if (page >= (r.meta.pagination?.lastPage ?: page) || r.data.isEmpty()) break
        }
        rows
    }

    suspend fun recentConsumption(branchId: String, ingredientId: String): ApiResult<List<InventoryConsumptionDto>> =
        apiCall { api.listInventoryConsumption(branchId, ingredientId, "CONSUMPTION", perPage = 5).data }

    suspend fun waste(branchId: String, ingredientId: String, quantity: String, reason: String): ApiResult<Unit> = apiCall {
        api.createInventoryMovement(branchId, key(), InventoryMovementRequest(ingredientId, "WASTE", quantity, reason.trim())).close()
    }

    suspend fun receive(branchId: String, ingredientId: String, quantity: String, unitCost: String, supplier: String): ApiResult<Unit> = apiCall {
        val body = InventoryReceiptRequest(
            supplierName = supplier.trim(),
            receivedAt = now(),
            lines = listOf(InventoryReceiptLineRequest(ingredientId, quantity, MoneyDto(unitCost, "MXN"))),
        )
        api.createInventoryReceipt(branchId, key(), body).close()
    }

    /**
     * Anota lo contado de un insumo en el conteo abierto de la sucursal; si no hay, lo abre. El gerente lo
     * aplica desde el panel. Si otro dispositivo cambió el conteo a la vez (409), se reintenta una vez.
     */
    suspend fun count(branchId: String, ingredientId: String, counted: String): ApiResult<InventoryCountDto> {
        repeat(2) { attempt ->
            val open = when (val r = openCount(branchId)) {
                is ApiResult.Failure -> return r
                is ApiResult.Success -> r.value
            }
            val lines = mergeCountLines(open.lines, InventoryCountLineDto(ingredientId, counted, now()))
            val saved = apiCall { api.replaceInventoryCountLines(branchId, open.id, "\"${open.version}\"", InventoryCountLinesRequest(lines)).data }
            val conflict = saved is ApiResult.Failure && (saved.failure as? ApiFailure.Server)?.status == 409
            if (!conflict || attempt == 1) return saved
        }
        error("inalcanzable")
    }

    suspend fun blockFoodSafety(branchId: String, ingredientId: String, reason: String): ApiResult<Unit> = apiCall {
        api.setFoodSafety(branchId, ingredientId, FoodSafetyRequest(blocked = true, reason = reason.trim())).close()
    }

    private suspend fun openCount(branchId: String): ApiResult<InventoryCountDto> {
        val found = apiCall { api.listInventoryCounts(branchId, "OPEN", 1).data.firstOrNull() }
        if (found !is ApiResult.Success || found.value != null) {
            @Suppress("UNCHECKED_CAST")
            return found as ApiResult<InventoryCountDto>
        }
        val created = apiCall { api.createInventoryCount(branchId, key(), InventoryCountCreateRequest()).data }
        // Otro dispositivo lo abrió en ese instante: se usa el suyo.
        if (created is ApiResult.Failure && (created.failure as? ApiFailure.Server)?.code == "INVENTORY_COUNT_ALREADY_OPEN") {
            return when (val again = apiCall { api.listInventoryCounts(branchId, "OPEN", 1).data.firstOrNull() }) {
                is ApiResult.Success -> again.value?.let { ApiResult.Success(it) } ?: created
                is ApiResult.Failure -> again
            }
        }
        return created
    }

    private fun now(): String = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun key(): String = UUID.randomUUID().toString()

    private companion object {
        const val PER_PAGE = 100
    }
}
