package com.restia.mobile.data.reports

import com.restia.mobile.core.network.ApiResult
import com.restia.mobile.core.network.DailySalesDto
import com.restia.mobile.core.network.HourlyOccupancyDto
import com.restia.mobile.core.network.SessionApi
import com.restia.mobile.core.network.apiCall
import java.time.LocalDate

/** Los dos reportes de un rango de días de negocio; las proyecciones se recalculan cada 15 s en el servidor. */
data class ReportData(val sales: List<DailySalesDto>, val occupancy: List<HourlyOccupancyDto>)

class ReportsRepository(private val api: SessionApi) {
    suspend fun load(branchId: String, from: LocalDate, to: LocalDate): ApiResult<ReportData> = apiCall {
        val sales = api.dailySales(branchId, from.toString(), to.toString()).data
        val occupancy = api.hourlyOccupancy(branchId, from.toString(), to.toString()).data
        ReportData(sales.sortedBy { it.businessDate }, occupancy)
    }
}
