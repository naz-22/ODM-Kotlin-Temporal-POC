package com.example.odm.application.ports.output

import com.example.odm.domain.model.AlertConsumer
import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeUpdate
import com.example.odm.domain.model.NotificationKind
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

// One consumer alert due `delaySeconds` after the deviation was created.
data class ScheduledAlert(
    val alertNumber: Int,
    val delaySeconds: Long,
    val consumers: List<AlertConsumer>
)

// Result of the business-rule check. autoRecoveryEligible is recorded for traceability only:
// the Full-Serve workflow has no auto-recovery path.
data class BusinessRuleDecision(
    val autoRecoveryEligible: Boolean,
    val alertsEnabled: Boolean,
    val ruleNames: List<String>,
    val alerts: List<ScheduledAlert>
)

@ActivityInterface
interface FullServeActivities {

    // Persists the deviation as OPEN. Returns false if a different OPEN deviation already
    // exists for the order. Idempotent: a retry for the same deviationId returns true again.
    @ActivityMethod
    fun createDeviation(deviationId: String, workflowId: String, runId: String, signal: FullServeSignal): Boolean

    @ActivityMethod
    fun notifyDeviationCreated(deviationId: String)

    @ActivityMethod
    fun checkBusinessRules(deviationId: String): BusinessRuleDecision

    @ActivityMethod
    fun publishConsumerAlert(deviationId: String, alertNumber: Int, consumer: AlertConsumer)

    // Called by the workflow once a publication has exhausted its retries.
    @ActivityMethod
    fun recordPublicationFailure(deviationId: String, kind: NotificationKind, recipient: String, alertNumber: Int?, reason: String)

    // Persists CLOSED with the update's trigger and source. Idempotent.
    @ActivityMethod
    fun closeDeviation(deviationId: String, update: FullServeUpdate)

    @ActivityMethod
    fun publishAlertClosure(deviationId: String, consumers: List<AlertConsumer>)

    @ActivityMethod
    fun notifyDeviationClosed(deviationId: String, alertsPublished: Int)
}
