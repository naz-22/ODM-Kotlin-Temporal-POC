package com.example.payment.application.service

import com.example.payment.application.ports.output.PaymentAttemptRepository
import com.example.payment.application.ports.output.PaymentEventPublisherPort
import com.example.payment.domain.model.PaymentAttempt
import com.example.payment.domain.model.PaymentAttemptStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class PaymentApplicationServiceTest {

    private val repository: PaymentAttemptRepository = mock()
    private val eventPublisher: PaymentEventPublisherPort = mock()
    private val service = PaymentApplicationService(repository, eventPublisher)

    @Test
    fun `successful charge publishes PaymentSucceeded`() {
        whenever(repository.findByOrderIdAndAttemptNumber("order-1", 1)).thenReturn(null)

        val outcome = service.charge("order-1", "customer-1", BigDecimal("100.00"), 1)

        assertEquals(true, outcome.succeeded)
        verify(eventPublisher).publishPaymentSucceeded("order-1", BigDecimal("100.00"))
    }

    @Test
    fun `failing customer publishes PaymentFailed`() {
        whenever(repository.findByOrderIdAndAttemptNumber("order-2", 1)).thenReturn(null)

        val outcome = service.charge("order-2", "cust-payment-fail-always", BigDecimal("100.00"), 1)

        assertEquals(false, outcome.succeeded)
        verify(eventPublisher).publishPaymentFailed(eq("order-2"), eq(1), any())
    }

    // (helper removed - using mockito-kotlin's eq() directly, imported above)

    @Test
    fun `retried attempt is idempotent and does not charge twice`() {
        whenever(repository.findByOrderIdAndAttemptNumber("order-3", 1)).thenReturn(
            PaymentAttempt("order-3", 1, "customer-1", BigDecimal("100.00"), PaymentAttemptStatus.SUCCEEDED, null, Instant.now())
        )

        val outcome = service.charge("order-3", "customer-1", BigDecimal("100.00"), 1)

        assertEquals(true, outcome.succeeded)
        verify(eventPublisher, never()).publishPaymentSucceeded(any(), any())
        verify(repository, never()).save(any())
    }
}
