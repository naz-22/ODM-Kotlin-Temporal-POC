package com.example.orderlifecycle.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

// Pure domain test - no Spring context, no DB, no Temporal. This is the fastest
// possible feedback loop for the one rule that must never be violated: "Domain
// rules must remain in Kotlin" and lifecycle transitions must be enforced here,
// not left open to an arbitrary PUT /orders/{id}/status caller.
class OrderStatusTransitionsTest {

    private fun sampleOrder(status: OrderStatus = OrderStatus.ORDER_PLACED): Order = Order(
        id = UUID.randomUUID(),
        customerId = "customer-1",
        productId = "PROD-1",
        quantity = 1,
        amount = BigDecimal("100.00"),
        status = status,
        createdAt = Instant.now()
    )

    @Test
    fun `happy path transitions are all legal in order`() {
        var order = sampleOrder(OrderStatus.ORDER_PLACED)
        val happyPath = listOf(
            OrderStatus.VALIDATING,
            OrderStatus.INVENTORY_RESERVATION_PENDING,
            OrderStatus.INVENTORY_RESERVED,
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PAYMENT_CONFIRMED,
            OrderStatus.ORDER_CONFIRMED,
            OrderStatus.NOTIFICATION_PENDING,
            OrderStatus.COMPLETED
        )

        for (next in happyPath) {
            order = order.moveTo(next)
            assertEquals(next, order.status)
        }
    }

    @Test
    fun `inventory unavailable path fails the order`() {
        val order = sampleOrder(OrderStatus.INVENTORY_RESERVATION_PENDING)
        val failed = order.moveTo(OrderStatus.ORDER_FAILED)
        assertEquals(OrderStatus.ORDER_FAILED, failed.status)
    }

    @Test
    fun `payment retry exhaustion cancels the order`() {
        val order = sampleOrder(OrderStatus.PAYMENT_PENDING)
        val cancelled = order.moveTo(OrderStatus.ORDER_CANCELLED)
        assertEquals(OrderStatus.ORDER_CANCELLED, cancelled.status)
    }

    @Test
    fun `cannot skip lifecycle stages`() {
        val order = sampleOrder(OrderStatus.ORDER_PLACED)
        assertFailsWith<IllegalStateException> {
            order.moveTo(OrderStatus.PAYMENT_CONFIRMED)
        }
    }

    @Test
    fun `terminal states accept no further transitions`() {
        val completed = sampleOrder(OrderStatus.COMPLETED)
        assertFailsWith<IllegalStateException> { completed.moveTo(OrderStatus.ORDER_PLACED) }

        val failed = sampleOrder(OrderStatus.ORDER_FAILED)
        assertFailsWith<IllegalStateException> { failed.moveTo(OrderStatus.VALIDATING) }

        val cancelled = sampleOrder(OrderStatus.ORDER_CANCELLED)
        assertFailsWith<IllegalStateException> { cancelled.moveTo(OrderStatus.VALIDATING) }
    }

    @Test
    fun `cannot re-enter an earlier stage`() {
        val order = sampleOrder(OrderStatus.PAYMENT_CONFIRMED)
        assertFailsWith<IllegalStateException> {
            order.moveTo(OrderStatus.PAYMENT_PENDING)
        }
    }
}
