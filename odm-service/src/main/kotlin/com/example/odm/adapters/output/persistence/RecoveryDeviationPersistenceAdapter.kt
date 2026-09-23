package com.example.odm.adapters.output.persistence

import com.example.odm.application.ports.output.RecoveryDeviationRepository
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class RecoveryDeviationPersistenceAdapter(
    private val jpaRepository: SpringDataRecoveryDeviationRepository
) : RecoveryDeviationRepository {

    override fun findByKey(deviationKey: String): RecoveryDeviation? =
        jpaRepository.findById(deviationKey).map(::toDomain).orElse(null)

    // Own transaction: commits before DeviationRecoveryService starts the workflow.
    @Transactional
    override fun insertIfAbsent(deviation: RecoveryDeviation): Boolean =
        jpaRepository.insertIfAbsent(
            deviationKey = deviation.deviationKey,
            orderId = deviation.orderId,
            type = deviation.type.name,
            releaseStage = deviation.releaseStage.name,
            market = deviation.market,
            itemId = deviation.itemId,
            requestedQuantity = deviation.requestedQuantity,
            pickedQuantity = deviation.pickedQuantity,
            reason = deviation.reason,
            autoRecoveryEnabled = deviation.autoRecoveryEnabled,
            decision = deviation.decision.name,
            decisionRule = deviation.decisionRule,
            status = deviation.status.name,
            workflowId = deviation.workflowId,
            createdAt = deviation.createdAt
        ) == 1

    override fun findUnresolvedCreatedBefore(cutoff: Instant): List<RecoveryDeviation> =
        jpaRepository.findByStatusNotAndCreatedAtBefore(RecoveryStatus.RESOLVED, cutoff).map(::toDomain)

    @Transactional
    override fun tryRecordWorkflowRestart(deviationKey: String, maxRestarts: Int): Boolean =
        jpaRepository.incrementWorkflowRestartsBelow(deviationKey, maxRestarts) == 1

    @Transactional
    override fun markResolvedEventPublished(deviationKey: String, at: Instant): Boolean =
        jpaRepository.markResolvedEventPublishedIfUnset(deviationKey, at) == 1

    override fun save(deviation: RecoveryDeviation): RecoveryDeviation {
        jpaRepository.save(toEntity(deviation))
        return deviation
    }

    private fun toDomain(e: RecoveryDeviationEntity) = RecoveryDeviation(
        deviationKey = e.deviationKey, orderId = e.orderId, type = e.type, releaseStage = e.releaseStage,
        market = e.market, itemId = e.itemId, requestedQuantity = e.requestedQuantity,
        pickedQuantity = e.pickedQuantity, reason = e.reason, autoRecoveryEnabled = e.autoRecoveryEnabled,
        decision = e.decision, decisionRule = e.decisionRule, status = e.status, resolution = e.resolution,
        resolutionNote = e.resolutionNote, workflowId = e.workflowId, createdAt = e.createdAt,
        resolvedAt = e.resolvedAt, workflowRestarts = e.workflowRestarts,
        resolvedEventPublishedAt = e.resolvedEventPublishedAt
    )

    private fun toEntity(d: RecoveryDeviation) = RecoveryDeviationEntity(
        deviationKey = d.deviationKey, orderId = d.orderId, type = d.type, releaseStage = d.releaseStage,
        market = d.market, itemId = d.itemId, requestedQuantity = d.requestedQuantity,
        pickedQuantity = d.pickedQuantity, reason = d.reason, autoRecoveryEnabled = d.autoRecoveryEnabled,
        decision = d.decision, decisionRule = d.decisionRule, status = d.status, resolution = d.resolution,
        resolutionNote = d.resolutionNote, workflowId = d.workflowId, createdAt = d.createdAt,
        resolvedAt = d.resolvedAt, workflowRestarts = d.workflowRestarts,
        resolvedEventPublishedAt = d.resolvedEventPublishedAt
    )
}
