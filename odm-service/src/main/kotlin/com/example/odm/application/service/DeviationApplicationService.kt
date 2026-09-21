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

        val deviation = existing ?: Deviation(
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

        val resolved = deviation.resolvedWith(decision)
        deviationRepository.save(resolved)

        log.info("Deviation decided: orderId={}, type={}, attempt={}, decision={}", orderId, type, attemptNumber, decision)
        return decision
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

        deviationRepository.save(
            Deviation(
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
        )
        log.info("Deviation recorded from Solace event: orderId={}, type={}, attempt={}", orderId, type, attemptNumber)
    }
}
