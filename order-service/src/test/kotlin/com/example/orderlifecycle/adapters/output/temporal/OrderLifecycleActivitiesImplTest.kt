package com.example.orderlifecycle.adapters.output.temporal

import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.application.ports.output.PaymentAttemptResult
import com.example.orderlifecycle.application.ports.output.PaymentClientPort
import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Focused on processPayment's behaviour when Temporal re-delivers the activity after a
// worker crash. The use case is an in-memory fake that goes through the real
// Order.moveTo, so the domain state machine is genuinely enforced here.
class OrderLifecycleActivitiesImplTest {

    private class InMemoryOrderUseCase(var order: Order) : PlaceOrderUseCase {
        override fun placeOrder(order: Order): Order = throw UnsupportedOperationException()
        override fun findOrderById(id: UUID): Order? = order.takeIf { it.id == id }
        override fun transitionOrder(id: UUID, newStatus: OrderStatus): Order {
            order = order.moveTo(newStatus)
            return order
        }
    }

    private val paymentClient: PaymentClientPort = mock()

    private fun orderIn(status: OrderStatus) = Order(
        id = UUID.randomUUID(),
        customerId = "customer-1",
        productId = "PROD-1",
        quantity = 1,
        amount = BigDecimal("100.00"),
        status = status,
        createdAt = Instant.now()
    )

    private fun activitiesFor(useCase: PlaceOrderUseCase) =
        OrderLifecycleActivitiesImpl(useCase, mock(), paymentClient, mock(), mock())

    @Test
    fun `first payment attempt moves INVENTORY_RESERVED to PAYMENT_PENDING then PAYMENT_CONFIRMED`() {
        val useCase = InMemoryOrderUseCase(orderIn(OrderStatus.INVENTORY_RESERVED))
        whenever(paymentClient.charge(any(), any(), any(), eq(1))).thenReturn(PaymentAttemptResult(true, null))

        val result = activitiesFor(useCase).processPayment(useCase.order.id.toString(), 1)

        assertEquals(true, result.succeeded)
        assertEquals(OrderStatus.PAYMENT_CONFIRMED, useCase.order.status)
    }

    @Test
    fun `re-delivered first attempt after worker crash does not repeat PAYMENT_PENDING transition`() {
        // The crashed run already committed PAYMENT_PENDING before the worker died.
        val useCase = InMemoryOrderUseCase(orderIn(OrderStatus.PAYMENT_PENDING))
        whenever(paymentClient.charge(any(), any(), any(), eq(1))).thenReturn(PaymentAttemptResult(true, null))

        val result = activitiesFor(useCase).processPayment(useCase.order.id.toString(), 1)

        assertEquals(true, result.succeeded)
        assertEquals(OrderStatus.PAYMENT_CONFIRMED, useCase.order.status)
        // Same attempt number as the crashed run - payment-service dedupes on it.
        verify(paymentClient, times(1)).charge(any(), any(), any(), eq(1))
    }

    @Test
    fun `first payment attempt from an illegal status is still rejected`() {
        val useCase = InMemoryOrderUseCase(orderIn(OrderStatus.VALIDATING))

        assertFailsWith<IllegalStateException> {
            activitiesFor(useCase).processPayment(useCase.order.id.toString(), 1)
        }
        verify(paymentClient, times(0)).charge(any(), any(), any(), any())
    }
}
