package com.example.odm.adapters.input.temporal

import com.example.odm.application.ports.input.FullServeDeviationWorkflow
import com.example.odm.application.ports.input.FullServeOutcome
import com.example.odm.application.ports.input.FullServeResult
import com.example.odm.application.ports.output.FullServeActivities
import com.example.odm.domain.model.AlertConsumer
import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeUpdate
import com.example.odm.domain.model.NotificationKind
import com.example.odm.domain.model.ResolutionStatus
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration

// Orchestration only - deterministic, no DB/Solace/Drools/Spring here. Every side effect is an
// Activity; every wait is a durable timer or a Signal-driven Workflow.await.
//
//   createDeviation -> notifyDeviationCreated (O360) -> checkBusinessRules
//   -> per scheduled alert: durable timer, then publishConsumerAlert per consumer
//   -> Workflow.await(resolving update)
//   -> closeDeviation -> publishAlertClosure (SPM/Dynamics) -> notifyDeviationClosed (O360)
class FullServeDeviationWorkflowImpl : FullServeDeviationWorkflow {

    private val log = Workflow.getLogger(FullServeDeviationWorkflowImpl::class.java)

    // Database work and rule evaluation: retried until it succeeds. A database outage must
    // delay the lifecycle, never abandon an OPEN deviation half-way.
    private val lifecycle = Workflow.newActivityStub(
        FullServeActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .build()
            )
            .build()
    )

    // Publications: bounded retries for transient broker failures. Once exhausted, the failure is
    // recorded (operational alert) and the lifecycle carries on rather than blocking the deviation.
    private val publications = Workflow.newActivityStub(
        FullServeActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(PUBLISH_ATTEMPTS)
                    .build()
            )
            .build()
    )

    // Workflow state, rebuilt from the event history on replay - it survives worker restarts.
    private var deviationId: String? = null
    private val receivedUpdates = mutableListOf<FullServeUpdate>()

    override fun handle(signal: FullServeSignal): FullServeOutcome {
        val info = Workflow.getInfo()
        // Workflow.randomUUID is replay-safe: the same id on every replay and Activity retry.
        val id = "FSD-${Workflow.randomUUID()}"
        deviationId = id

        if (!lifecycle.createDeviation(id, info.workflowId, info.runId, signal)) {
            log.info("Order {} already has an OPEN deviation - signal ignored", signal.orderId)
            return FullServeOutcome(id, FullServeResult.IGNORED_OPEN_EXISTS)
        }
        val createdAt = Workflow.currentTimeMillis()
        publish(id, NotificationKind.DEVIATION_CREATED, O360) { publications.notifyDeviationCreated(id) }

        val decision = lifecycle.checkBusinessRules(id)
        // Full Serve has no auto-recovery path, whatever the rules report: autoRecoveryEligible is
        // recorded only. A future AutoRecoveryWorkflow for eligible service types would branch here.

        val alerted = linkedSetOf<AlertConsumer>()
        var alertsPublished = 0
        for (alert in decision.alerts.sortedBy { it.delaySeconds }) {
            val untilDue = Duration.ofMillis(createdAt + alert.delaySeconds * 1000 - Workflow.currentTimeMillis())
            // Durable timer that also wakes as soon as a resolving update arrives. No worker
            // thread is held while waiting; a closed deviation skips every remaining alert.
            if (untilDue > Duration.ZERO) Workflow.await(untilDue) { resolvingUpdate() != null }
            if (resolvingUpdate() != null) break
            for (consumer in alert.consumers) {
                val published = publish(id, NotificationKind.CONSUMER_ALERT, consumer.name, alert.alertNumber) {
                    publications.publishConsumerAlert(id, alert.alertNumber, consumer)
                }
                if (published) {
                    alerted += consumer
                    alertsPublished++
                }
            }
        }

        // Parked until a resolving update arrives - minutes or weeks. Nothing polls.
        Workflow.await { resolvingUpdate() != null }
        val resolution = resolvingUpdate()!!

        lifecycle.closeDeviation(id, resolution)
        // Alert closure goes only to consumers that actually received an alert.
        if (alerted.isNotEmpty()) {
            publish(id, NotificationKind.ALERT_CLOSURE, alerted.joinToString(",")) {
                publications.publishAlertClosure(id, alerted.toList())
            }
        }
        publish(id, NotificationKind.DEVIATION_CLOSED, O360) { publications.notifyDeviationClosed(id, alertsPublished) }
        return FullServeOutcome(id, FullServeResult.CLOSED, alertsPublished)
    }

    // Runs one publication Activity; after its retries are exhausted, records the failure and
    // returns false instead of failing the workflow.
    private fun publish(id: String, kind: NotificationKind, recipient: String, alertNumber: Int? = null, call: () -> Unit): Boolean =
        try {
            call()
            true
        } catch (e: ActivityFailure) {
            val reason = e.cause?.message ?: e.message ?: "unknown"
            lifecycle.recordPublicationFailure(id, kind, recipient, alertNumber, reason)
            false
        }

    // The first update that resolves this deviation. Non-resolving updates and updates naming a
    // different (earlier) deviation of the same order are kept in history but never close it.
    private fun resolvingUpdate(): FullServeUpdate? = receivedUpdates.firstOrNull {
        it.resolutionStatus == ResolutionStatus.RESOLVED && (it.deviationId == null || it.deviationId == deviationId)
    }

    // Only records the payload; Workflow.await above re-evaluates its condition because of it.
    override fun resolutionUpdate(update: FullServeUpdate) {
        receivedUpdates += update
    }

    private companion object {
        const val O360 = "O360"
        const val PUBLISH_ATTEMPTS = 5
    }
}
