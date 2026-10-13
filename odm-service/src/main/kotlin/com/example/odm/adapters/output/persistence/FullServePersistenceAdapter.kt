package com.example.odm.adapters.output.persistence

import com.example.odm.application.ports.output.FullServeDeviationRepository
import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeNotification
import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeStatus
import com.example.odm.domain.model.FullServeUpdate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// Each write runs in its own short transaction and is committed when the Activity returns, so
// Temporal never records an Activity as complete for a change that could still roll back.
@Component
class FullServePersistenceAdapter(
    private val deviations: SpringDataFullServeDeviationRepository,
    private val notifications: SpringDataFullServeNotificationRepository
) : FullServeDeviationRepository {

    override fun findById(deviationId: String): FullServeDeviation? =
        deviations.findById(deviationId).map(::toDomain).orElse(null)

    override fun findOpenByOrderId(orderId: String): FullServeDeviation? =
        deviations.findByOrderIdAndStatus(orderId, FullServeStatus.OPEN)?.let(::toDomain)

    override fun findByOrderId(orderId: String): List<FullServeDeviation> =
        deviations.findByOrderIdOrderByCreatedAt(orderId).map(::toDomain)

    @Transactional
    override fun insertIfAbsent(deviation: FullServeDeviation): Boolean {
        val s = deviation.signal
        return deviations.insertIfAbsent(
            deviation.deviationId, s.orderId, s.source.name, s.serviceType, s.market, s.deviationType,
            s.reasonCode, s.occurrence, s.reasonCaused, deviation.workflowId, deviation.runId, deviation.createdAt
        ) == 1
    }

    @Transactional
    override fun recordBusinessRule(deviationId: String, businessRule: String) {
        deviations.updateBusinessRule(deviationId, businessRule.take(500))
    }

    @Transactional
    override fun closeIfOpen(deviationId: String, update: FullServeUpdate, at: Instant): Boolean =
        deviations.closeIfOpen(deviationId, update.trigger.name, update.source.name, update.note, at) == 1

    override fun findNotification(notificationKey: String): FullServeNotification? =
        notifications.findById(notificationKey).map(::toDomain).orElse(null)

    override fun findNotifications(deviationId: String): List<FullServeNotification> =
        notifications.findByDeviationIdOrderByRecordedAt(deviationId).map(::toDomain)

    @Transactional
    override fun saveNotification(notification: FullServeNotification) {
        with(notification) {
            notifications.save(
                FullServeNotificationEntity(notificationKey, deviationId, kind, recipient, destination, status, detail, recordedAt)
            )
        }
    }

    private fun toDomain(e: FullServeDeviationEntity) = FullServeDeviation(
        deviationId = e.deviationId,
        workflowId = e.workflowId,
        runId = e.runId,
        signal = FullServeSignal(e.orderId, e.source, e.serviceType, e.market, e.deviationType, e.reasonCode, e.occurrence, e.reasonCaused),
        status = e.status,
        businessRule = e.businessRule,
        closureTrigger = e.closureTrigger,
        closureSource = e.closureSource,
        closureNote = e.closureNote,
        createdAt = e.createdAt,
        closedAt = e.closedAt
    )

    private fun toDomain(e: FullServeNotificationEntity) = FullServeNotification(
        e.notificationKey, e.deviationId, e.kind, e.recipient, e.destination, e.status, e.detail, e.recordedAt
    )
}
