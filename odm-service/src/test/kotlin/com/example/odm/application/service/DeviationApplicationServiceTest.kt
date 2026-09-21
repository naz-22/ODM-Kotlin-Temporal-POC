package com.example.odm.application.service

import com.example.odm.application.ports.output.DeviationRepository
import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationStatus
import com.example.odm.domain.model.DeviationType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
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
        whenever(repository.createIfAbsent(any())).thenAnswer { it.arguments[0] }

        val decision = service.decide("order-1", DeviationType.INVENTORY_UNAVAILABLE, 0, "no stock")

        assertEquals(Decision.FAIL_ORDER, decision)
        verify(repository).createIfAbsent(any())
    }

    @Test
    fun `payment deviation with attempts left decides RETRY`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-2", DeviationType.PAYMENT_FAILED, 1))
            .thenReturn(null)
        whenever(repository.createIfAbsent(any())).thenAnswer { it.arguments[0] }

        val decision = service.decide("order-2", DeviationType.PAYMENT_FAILED, 1, "declined")

        assertEquals(Decision.RETRY, decision)
    }

    @Test
    fun `payment deviation on exhausted attempt decides COMPENSATE`() {
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-3", DeviationType.PAYMENT_FAILED, 3))
            .thenReturn(null)
        whenever(repository.createIfAbsent(any())).thenAnswer { it.arguments[0] }

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
        whenever(repository.createIfAbsent(any())).thenAnswer { it.arguments[0] }

        service.recordObservedDeviation("order-5", DeviationType.INVENTORY_UNAVAILABLE, 0, "no stock")

        verify(repository).createIfAbsent(any())
    }

    // --- Race-condition / idempotency coverage -----------------------------------
    // These simulate what createIfAbsent()'s contract guarantees a concurrent caller
    // sees (it never throws - it returns whichever row actually won the DB-level
    // ON CONFLICT DO NOTHING race), and assert decide()/recordObservedDeviation()
    // converge correctly instead of creating a second row or raising an exception.

    @Test
    fun `decide converges on a decision already committed by a concurrent decide call`() {
        // Simulates: two concurrent decide() calls for the same key both pass the
        // initial "no existing row" check, both race into createIfAbsent(), and the
        // other caller's fully-decided row wins.
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-6", DeviationType.PAYMENT_FAILED, 1))
            .thenReturn(null)
        val winningRow = Deviation(
            id = UUID.randomUUID(), orderId = "order-6", type = DeviationType.PAYMENT_FAILED,
            attemptNumber = 1, reason = "declined", status = DeviationStatus.RESOLVED,
            decision = Decision.RETRY, createdAt = Instant.now(), resolvedAt = Instant.now()
        )
        whenever(repository.createIfAbsent(any())).thenReturn(winningRow)

        val decision = service.decide("order-6", DeviationType.PAYMENT_FAILED, 1, "declined")

        assertEquals(Decision.RETRY, decision)
        // No second insert, and the winner's row is never overwritten.
        verify(repository, never()).save(any())
    }

    @Test
    fun `decide converges on a Solace-recorded placeholder that wins the create race`() {
        // Simulates: decide()'s createIfAbsent() loses to a concurrent Solace
        // delivery that recorded a placeholder row with no decision yet. decide()
        // must finish the job by filling in the decision on that same row.
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-7", DeviationType.INVENTORY_UNAVAILABLE, 0))
            .thenReturn(null)
        val placeholderRow = Deviation(
            id = UUID.randomUUID(), orderId = "order-7", type = DeviationType.INVENTORY_UNAVAILABLE,
            attemptNumber = 0, reason = "no stock", status = DeviationStatus.OPEN,
            decision = null, createdAt = Instant.now(), resolvedAt = null
        )
        whenever(repository.createIfAbsent(any())).thenReturn(placeholderRow)

        val decision = service.decide("order-7", DeviationType.INVENTORY_UNAVAILABLE, 0, "no stock")

        assertEquals(Decision.FAIL_ORDER, decision)
        // Exactly one save: filling in the decision on the winner's own row.
        verify(repository).save(argThat<Deviation> { d -> d.id == placeholderRow.id && d.decision == Decision.FAIL_ORDER })
    }

    @Test
    fun `recordObservedDeviation converges without error when it loses the create race`() {
        // Simulates: the Solace consumer's createIfAbsent() loses to a row the
        // synchronous decide() path (or another redelivery) already created.
        whenever(repository.findByOrderIdAndTypeAndAttemptNumber("order-8", DeviationType.PAYMENT_FAILED, 2))
            .thenReturn(null)
        val someoneElsesRow = Deviation(
            id = UUID.randomUUID(), orderId = "order-8", type = DeviationType.PAYMENT_FAILED,
            attemptNumber = 2, reason = "declined", status = DeviationStatus.RESOLVED,
            decision = Decision.COMPENSATE, createdAt = Instant.now(), resolvedAt = Instant.now()
        )
        whenever(repository.createIfAbsent(any())).thenReturn(someoneElsesRow)

        service.recordObservedDeviation("order-8", DeviationType.PAYMENT_FAILED, 2, "declined")

        // No exception, no second row, and the winner's existing decision is untouched.
        verify(repository, never()).save(any())
    }
}
