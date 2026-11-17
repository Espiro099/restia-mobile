package com.restia.mobile

import com.restia.mobile.core.network.CheckDto
import com.restia.mobile.core.network.MoneyDto
import com.restia.mobile.core.network.OrderDto
import com.restia.mobile.core.network.OrderItemDto
import com.restia.mobile.core.network.OrderUnitDto
import com.restia.mobile.ui.checks.tableBalance
import com.restia.mobile.ui.checks.tableTotal
import com.restia.mobile.ui.orders.roundStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Seguimiento de la ronda y saldo de la mesa, lo que muestran las pantallas rediseñadas. */
class ProgressTest {
    private fun round(vararg statuses: String) = OrderDto(
        id = "o1",
        clientOrderUuid = "c1",
        status = "SENT",
        total = MoneyDto("0.00", "MXN"),
        items = listOf(
            OrderItemDto(
                id = "i1",
                name = "Sopa",
                quantity = statuses.size,
                units = statuses.mapIndexed { i, s -> OrderUnitDto("u$i", s, 1) },
            ),
        ),
    )

    @Test
    fun `la ronda va en el paso de su unidad mas atrasada`() {
        assertEquals(0, roundStage(round("PENDING", "READY")))
        assertEquals(1, roundStage(round("PREPARING", "DELIVERED")))
        assertEquals(2, roundStage(round("READY", "READY")))
        assertEquals(3, roundStage(round("DELIVERED")))
    }

    @Test
    fun `lo cancelado o anulado no cuenta para el seguimiento`() {
        assertEquals(3, roundStage(round("DELIVERED", "CANCELLED", "VOIDED")))
        assertNull(roundStage(round("CANCELLED", "VOIDED")))
        assertNull(roundStage(round()))
    }

    private fun check(status: String, balance: String, currency: String = "MXN", total: String = "0.00"): CheckDto {
        val zero = MoneyDto("0.00", currency)
        return CheckDto(
            id = "c-$status-$balance",
            sessionId = "s1",
            number = 1,
            status = status,
            subtotal = zero,
            taxTotal = zero,
            adjustmentTotal = zero,
            total = MoneyDto(total, currency),
            paidTotal = zero,
            pendingTotal = zero,
            tipTotal = zero,
            balance = MoneyDto(balance, currency),
            version = 1,
        )
    }

    @Test
    fun `el saldo de la mesa suma las cuentas por cobrar`() {
        val balance = tableBalance(listOf(check("OPEN", "150.50"), check("PARTIALLY_PAID", "49.5"), check("PAID", "0.00")))
        assertEquals(MoneyDto("200.00", "MXN"), balance)
    }

    @Test
    fun `reembolsadas y canceladas no entran al saldo`() {
        val balance = tableBalance(listOf(check("OPEN", "10.00"), check("REFUNDED", "-80.00"), check("CANCELLED", "5.00")))
        assertEquals(MoneyDto("10.00", "MXN"), balance)
    }

    @Test
    fun `el total de la mesa pagada deja fuera lo reembolsado`() {
        val total = tableTotal(listOf(check("PAID", "0.00", total = "135"), check("PAID", "0.00", total = "40.00"), check("REFUNDED", "-60.00", total = "60.00")))
        assertEquals(MoneyDto("175.00", "MXN"), total)
    }

    @Test
    fun `sin una sola moneda no hay saldo que mostrar`() {
        assertNull(tableBalance(listOf(check("OPEN", "10.00"), check("OPEN", "3.00", "USD"))))
        assertNull(tableBalance(emptyList()))
    }
}
