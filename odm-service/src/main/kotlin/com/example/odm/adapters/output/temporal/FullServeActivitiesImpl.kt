package com.example.odm.adapters.output.temporal

import com.example.odm.application.ports.output.BusinessRuleDecision
import com.example.odm.application.ports.output.FullServeActivities
import com.example.odm.application.ports.output.FullServeDeviationRepository
import com.example.odm.application.ports.output.FullServePublisherPort
import com.example.odm.application.ports.output.ScheduledAlert
import com.example.odm.application.service.FullServeRulesEngine
import com.example.odm.configuration.FullServeProperties
import com.example.odm.domain.model.AlertConsumer
import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeNotification
import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeStatus
import com.example.odm.domain.model.FullServeUpdate
import com.example.odm.domain.model.NotificationKind
import com.example.odm.domain.model.NotificationStatus
import com.example.odm.rules.FullServeDeviationFact
import io.temporal.failure.ApplicationFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

// Every side effect of the Full-Serve workflow: database writes, Drools, and publications.
// Each method is safe to repeat, because Temporal retries an Activity whose outcome it did not see.
@Component
class FullServeActivitiesImpl(
    private val repository: FullServeDeviationRepository,
    private val publisher: FullServePublisherPort,
    private val rulesEngine: FullServeRulesEngine,
    private val properties: FullServeProperties
) : FullServeActivities {

    private val log = LoggerFactory.getLogger(FullServeActivitiesImpl::class.java)

    override fun createDeviation(deviationId: String, workflowId: String, runId: String, signal: FullServeSignal): Boolean {
        if (repository.insertIfAbsent(FullServeDeviation.open(deviationId, workflowId, runId, signal, Instant.now()))) {
            log.info("Deviation {} created OPEN for order {} ({} / {})", deviationId, signal.orderId, signal.deviationType, signal.reasonCode)
            return true
        }
        // The insert conflicted: either an earlier attempt of this Activity already committed the
        // row (a retry), or a different OPEN deviation exists for the order (a duplicate).
        if (repository.findById(deviationId) != null) return true
        log.info("Order {} already has OPEN deviation {} - not creating another", signal.orderId,
            repository.findOpenByOrderId(signal.orderId)?.deviationId)
        return false
    }

    override fun notifyDeviationCreated(deviationId: String) {
        val d = load(deviationId)
        publishOnce(d, NotificationKind.DEVIATION_CREATED, O360, null, properties.destinations.deviationCreated,
            mapOf("eventType" to "DeviationCreated", "status" to d.status.name) + signalFields(d))
    }

    override fun checkBusinessRules(deviationId: String): BusinessRuleDecision {
        val d = load(deviationId)
        val s = d.signal
        val verdict = try {
            rulesEngine.evaluate(
                FullServeDeviationFact(deviationId, s.serviceType, s.market, s.deviationType, s.reasonCode,
                    properties.alerts.isEnabledFor(s.market))
            )
        } catch (e: IllegalStateException) {
            // Retrying cannot make a missing rule fire.
            throw ApplicationFailure.newNonRetryableFailure(e.message, "FullServeRulesDefect")
        }
        repository.recordBusinessRule(deviationId, verdict.ruleNames.joinToString("; "))

        val alerts = if (!verdict.alertsEnabled) emptyList() else properties.alerts.schedule.mapIndexed { i, delay ->
            ScheduledAlert(alertNumber = i + 1, delaySeconds = delay.seconds, consumers = properties.alerts.consumers)
        }
        log.info("Deviation {} rules {} -> autoRecoveryEligible={}, alerts={}", deviationId, verdict.ruleNames,
            verdict.autoRecoveryEligible, alerts.map { "#${it.alertNumber}@${it.delaySeconds}s" })
        return BusinessRuleDecision(verdict.autoRecoveryEligible, verdict.alertsEnabled, verdict.ruleNames, alerts)
    }

    override fun publishConsumerAlert(deviationId: String, alertNumber: Int, consumer: AlertConsumer) {
        val d = load(deviationId)
        if (d.status != FullServeStatus.OPEN) {
            log.info("Deviation {} is {} - consumer alert #{} to {} not published", deviationId, d.status, alertNumber, consumer)
            return
        }
        val destination = properties.destinations.consumerAlert
            .replace("{consumer}", consumer.name)
            .replace("{orderId}", d.orderId)
        publishOnce(d, NotificationKind.CONSUMER_ALERT, consumer.name, alertNumber, destination,
            mapOf("eventType" to "ConsumerAlert", "consumer" to consumer.name, "alertNumber" to alertNumber,
                "businessRule" to d.businessRule) + signalFields(d))
    }

    override fun recordPublicationFailure(deviationId: String, kind: NotificationKind, recipient: String, alertNumber: Int?, reason: String) {
        val key = FullServeNotification.keyFor(deviationId, kind, recipient, alertNumber)
        // Stands in for the support team's monitoring alert: a distinct, searchable ERROR line.
        log.error("[ODM operational alert] {} to {} for deviation {} failed after all retries: {}", kind, recipient, deviationId, reason)
        repository.saveNotification(
            FullServeNotification(key, deviationId, kind, recipient, destination = "-", NotificationStatus.FAILED, reason.take(500), Instant.now())
        )
    }

    override fun closeDeviation(deviationId: String, update: FullServeUpdate) {
        if (repository.closeIfOpen(deviationId, update, Instant.now())) {
            log.info("Deviation {} CLOSED by {} update from {}", deviationId, update.trigger, update.source)
        } else {
            log.info("Deviation {} was already CLOSED", deviationId)
        }
    }

    override fun publishAlertClosure(deviationId: String, consumers: List<AlertConsumer>) {
        val d = load(deviationId)
        publishOnce(d, NotificationKind.ALERT_CLOSURE, consumers.joinToString(","), null, properties.destinations.alertClosure,
            mapOf("eventType" to "DeviationClosed", "consumers" to consumers.map { it.name }) + closureFields(d))
    }

    override fun notifyDeviationClosed(deviationId: String, alertsPublished: Int) {
        val d = load(deviationId)
        publishOnce(d, NotificationKind.DEVIATION_CLOSED, O360, null, properties.destinations.deviationClosed,
            mapOf("eventType" to "DeviationClosedLcm", "alertsSent" to alertsPublished,
                "closureSource" to d.closureSource?.name, "closureNote" to d.closureNote) + closureFields(d))
    }

    // Publish-once: a retry after a successful publish finds the PUBLISHED row and skips. Only a
    // crash between publishing and recording can repeat a message - with the same eventId, so a
    // consumer can discard it.
    private fun publishOnce(
        d: FullServeDeviation, kind: NotificationKind, recipient: String, alertNumber: Int?,
        destination: String, payload: Map<String, Any?>
    ) {
        val key = FullServeNotification.keyFor(d.deviationId, kind, recipient, alertNumber)
        if (repository.findNotification(key)?.status == NotificationStatus.PUBLISHED) {
            log.info("{} already published - skipping", key)
            return
        }
        publisher.publish(destination, mapOf("eventId" to key) + payload)
        repository.saveNotification(
            FullServeNotification(key, d.deviationId, kind, recipient, destination, NotificationStatus.PUBLISHED, null, Instant.now())
        )
    }

    private fun signalFields(d: FullServeDeviation) = mapOf(
        "deviationId" to d.deviationId,
        "orderId" to d.orderId,
        "source" to d.signal.source.name,
        "serviceType" to d.signal.serviceType,
        "market" to d.signal.market,
        "deviationType" to d.signal.deviationType,
        "reasonCode" to d.signal.reasonCode,
        "occurrence" to d.signal.occurrence,
        "reasonCaused" to d.signal.reasonCaused,
        "createdAt" to d.createdAt.toString()
    )

    private fun closureFields(d: FullServeDeviation) = mapOf(
        "deviationId" to d.deviationId,
        "orderId" to d.orderId,
        "status" to d.status.name,
        "closureTrigger" to d.closureTrigger?.name,
        "closedAt" to d.closedAt?.toString()
    )

    private fun load(deviationId: String): FullServeDeviation =
        repository.findById(deviationId) ?: error("Unknown Full-Serve deviation $deviationId")

    private companion object {
        const val O360 = "O360"
    }
}
