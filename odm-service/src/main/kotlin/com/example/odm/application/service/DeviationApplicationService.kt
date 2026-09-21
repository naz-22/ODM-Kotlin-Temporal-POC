package com.example.odm.application.service

import com.example.odm.application.ports.input.DeviationDecisionUseCase
import com.example.odm.application.ports.output.DeviationRepository
import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationStatus
import com.example.odm.domain.model.DeviationType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DeviationApplicationService(
    private val deviationRepository: DeviationRepository,
    private val decisionEngine: DroolsDecisionEngine,
    @Value("\${payment.max-attempts:3}") private val maxPaymentAttempts: Int
) : DeviationDecisionUseCase {

    private val log = LoggerFactory.getLogger(DeviationApplicationService::class.java)

    @Transactional
    override fun decide(orderId: String, type: DeviationType, attemptNumber: Int, reason: String): Decision {
        val existing = deviationRepository.findByOrderIdAndTypeAndAttemptNumber(orderId, type, attemptNumber)

        // Idempotent replay: Temporal may call this Activity more than once for the
        // exact same attempt (technical retry). Once a decision has been made for a
        // given (orderId, type, attemptNumber), always return that same decision -
        // never re-run Drools and risk a different answer for the same fact.
        existing?.decision?.let {
            log.info("Decision already made: orderId={}, type={}, attempt={} -> {}", orderId, type, attemptNumber, it)
            return it
        }

        val decision = when (type) {
            DeviationType.INVENTORY_UNAVAILABLE ->
                decisionEngine.decideForInventoryUnavailable(orderId, reason)

            DeviationType.PAYMENT_FAILED ->
                decisionEngine.decideForPaymentFailed(orderId, attemptNumber, maxPaymentAttempts, reason)

            DeviationType.PAYMENT_RETRY_EXHAUSTED ->
                throw IllegalArgumentException(
                    "PAYMENT_RETRY_EXHAUSTED is a derived outcome (decision=COMPENSATE), not a request type"
                )
        }

        // A row already exists (created by the Solace-observed path) but has no
        // decision yet - fill it in on that same row. This is a plain update keyed by
        // the row's own id, so no insert race is possible here.
        if (existing != null) {
            deviationRepository.save(existing.resolvedWith(decision))
            log.info("Deviation decided: orderId={}, type={}, attempt={}, decision={}", orderId, type, attemptNumber, decision)
            return decision
        }

        // No row exists yet for this key. This is exactly where a concurrent Solace
        // delivery, or a duplicate decide() call, can race to create the first row -
        // createIfAbsent() is race-safe around the deviations table's own unique
        // constraint, so this never throws even if we lose the race.
        val fresh = newOpenDeviation(orderId, type, attemptNumber, reason).resolvedWith(decision)
        val winner = deviationRepository.createIfAbsent(fresh)

        val finalDecision = winner.decision ?: run {
            // Lost the race to a Solace-recorded placeholder (no decision yet) -
            // finish the job by filling in the decision on that same row, exactly
            // like the existing-row branch above.
            deviationRepository.save(winner.resolvedWith(decision))
            decision
        }

        log.info("Deviation decided: orderId={}, type={}, attempt={}, decision={}", orderId, type, attemptNumber, finalDecision)
        return finalDecision
    }

    override fun findById(id: UUID): Deviation? = deviationRepository.findById(id)

    override fun findByOrderId(orderId: String): List<Deviation> = deviationRepository.findByOrderId(orderId)

    // Used only by the Solace consumer (the asynchronous path). It records that a
    // deviation-worthy event happened, but never decides anything - decision-making
    // stays exclusively in decide() above, so the two paths can never disagree.
    @Transactional
    fun recordObservedDeviation(orderId: String, type: DeviationType, attemptNumber: Int, reason: String) {
        val existing = deviationRepository.findByOrderIdAndTypeAndAttemptNumber(orderId, type, attemptNumber)
        if (existing != null) {
            log.info("Deviation already recorded for orderId={}, type={}, attempt={} - Solace path converges, no-op",
                orderId, type, attemptNumber)
            return
        }

        val fresh = newOpenDeviation(orderId, type, attemptNumber, reason)

        // createIfAbsent() is race-safe: if the synchronous decide() path (or another
        // Solace redelivery) already created this row, we simply converge on it - no
        // second row, and whatever decision that row may already carry is untouched.
        val winner = deviationRepository.createIfAbsent(fresh)
        if (winner.id == fresh.id) {
            log.info("Deviation recorded from Solace event: orderId={}, type={}, attempt={}", orderId, type, attemptNumber)
        } else {
            log.info(
                "Deviation already recorded by a concurrent caller for orderId={}, type={}, attempt={} - Solace path converges, no-op",
                orderId, type, attemptNumber
            )
        }
    }

    private fun newOpenDeviation(orderId: String, type: DeviationType, attemptNumber: Int, reason: String) = Deviation(
        id = UUID.randomUUID(),
        orderId = orderId,
        type = type,
        attemptNumber = attemptNumber,
        reason = reason,
        status = DeviationStatus.OPEN,
        decision = null,
        createdAt = Instant.now(),
        resolvedAt = null
    )
}
