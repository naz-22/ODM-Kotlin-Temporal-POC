package com.example.odm.application.service

import com.example.odm.application.ports.output.DeviationRepository
import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationStatus
import com.example.odm.domain.model.DeviationType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class DeviationApplicationServiceTest {

    private val repository: DeviationRepository = mock()
    // Real Drools engine, not mocked - only the repository (I/O) is a test double.
    private val decisionEngine = DroolsDecisionEngine()

    private val service = DeviationApplicationService(repository, decisionEngine, maxPaymentAttempts = 3)

    @Test
    fun `inventory deviation decides FAIL_ORDER and persists it`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-1", DeviationType.INVENTORY_UNAVAILABLE, 0))
            .thenReturn(null)

        val decision = service.decide("order-1", DeviationType.INVENTORY_UNAVAILABLE, 0, "no stock")

        assertEquals(Decision.FAIL_ORDER, decision)
        verify(repository).save(any())
    }

    @Test
    fun `payment deviation with attempts left decides RETRY`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-2", DeviationType.PAYMENT_FAILED, 1))
            .thenReturn(null)

        val decision = service.decide("order-2", DeviationType.PAYMENT_FAILED, 1, "declined")

        assertEquals(Decision.RETRY, decision)
    }

    @Test
    fun `payment deviation on exhausted attempt decides COMPENSATE`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-3", DeviationType.PAYMENT_FAILED, 3))
            .thenReturn(null)

        val decision = service.decide("order-3", DeviationType.PAYMENT_FAILED, 3, "declined")

        assertEquals(Decision.COMPENSATE, decision)
    }

    @Test
    fun `a repeated decision call for the same attempt is idempotent`() {
        val alreadyDecided = Deviation(
            id = UUID.randomUUID(), orderId = "order-4", type = DeviationType.PAYMENT_FAILED,
            attemptNumber = 1, reason = "declined", status = DeviationStatus.RESOLVED,
            decision = Decision.RETRY, createdAt = Instant.now(), resolvedAt = Instant.now()
        )
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-4", DeviationType.PAYMENT_FAILED, 1))
            .thenReturn(alreadyDecided)

        val decision = service.decide("order-4", DeviationType.PAYMENT_FAILED, 1, "declined")

        assertEquals(Decision.RETRY, decision)
        verify(repository, never()).save(any())
    }

    @Test
    fun `recordObservedDeviation is idempotent and does not decide anything`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-5", DeviationType.INVENTORY_UNAVAILABLE, 0))
            .thenReturn(null)

        service.recordObservedDeviation("order-5", DeviationType.INVENTORY_UNAVAILABLE, 0, "no stock")

        verify(repository).save(any())
    }
}
