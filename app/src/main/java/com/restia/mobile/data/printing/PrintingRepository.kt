package com.restia.mobile.data.printing

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.PrintJobDto
import com.restia.mobile.core.network.PrintJobResolveRequest
import com.restia.mobile.core.network.PrintJobRetryRequest
import com.restia.mobile.core.network.ReasonRequest
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall

/** Trabajos de impresión de la sucursal (Decisiones_Printing): ver si salieron y resolver los que no. */
class PrintingRepository(private val api: SessionApi) {
    /** FAILED y UNKNOWN: lo que alguien tiene que atender. Del más reciente al más antiguo. */
    suspend fun attention(branchId: String): ApiResult<List<PrintJobDto>> = apiCall {
        ATTENTION.flatMap { status -> api.listPrintJobs(branchId, status, 1, PER_PAGE).data }
            .filter { it.status in ATTENTION }
            .sortedByDescending { it.createdAt }
    }

    /** Los últimos trabajos, de cualquier estado. */
    suspend fun recent(branchId: String): ApiResult<List<PrintJobDto>> = apiCall { api.listPrintJobs(branchId, null, 1, PER_PAGE).data }

    /** Nombre de cada impresora por su id. */
    suspend fun printerNames(branchId: String): ApiResult<Map<String, String>> = apiCall {
        api.listPrinters(branchId, 1, PER_PAGE).data.associate { it.id to it.name }
    }

    suspend fun retry(branchId: String, job: PrintJobDto): ApiResult<PrintJobDto> =
        apiCall { api.retryPrintJob(branchId, job.id, "\"${job.version}\"", PrintJobRetryRequest()).data }

    suspend fun resolve(branchId: String, job: PrintJobDto, printed: Boolean, reason: String): ApiResult<PrintJobDto> = apiCall {
        api.resolvePrintJob(branchId, job.id, "\"${job.version}\"", PrintJobResolveRequest(if (printed) "PRINTED" else "NOT_PRINTED", reason)).data
    }

    suspend fun cancel(branchId: String, job: PrintJobDto, reason: String): ApiResult<PrintJobDto> =
        apiCall { api.cancelPrintJob(branchId, job.id, "\"${job.version}\"", ReasonRequest(reason)).data }

    suspend fun reprint(branchId: String, job: PrintJobDto, idempotencyKey: String, reason: String): ApiResult<PrintJobDto> =
        apiCall { api.reprintPrintJob(branchId, job.id, idempotencyKey, ReasonRequest(reason)).data }

    private companion object {
        val ATTENTION = listOf("FAILED", "UNKNOWN")
        const val PER_PAGE = 50
    }
}
