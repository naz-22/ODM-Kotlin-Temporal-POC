package com.example.odm.domain.model

import java.time.Instant

// Full-Serve deviation lifecycle. Separate from the order-lifecycle Deviation and the
// autorecovery RecoveryDeviation models, which stay exactly as they are.

// Who published the signal or update. OE publishes every deviation type; OEP publishes the
// Payment deviation type. O360 is listed as an update source by the ODM flow.
enum class SignalSource { OE, OEP, O360 }

enum class FullServeStatus { OPEN, CLOSED }

// The three closure triggers of the ODM flow that apply to Full Serve. AUTO_RECOVERED is
// deliberately absent: Full-Serve deviations are not eligible for auto-recovery.
enum class ClosureTrigger { CUSTOMER, COWORKER, AUTO_CLOSURE }

// Whether an update reports the deviation as resolved. Only RESOLVED closes it; anything else
// leaves it OPEN. (POC assumption: the update contract's resolution status is not yet agreed.)
enum class ResolutionStatus { RESOLVED, UNRESOLVED }

// Consumer systems that receive consumer alerts and alert-closure notifications.
enum class AlertConsumer { SPM, DYNAMICS }

enum class NotificationKind { DEVIATION_CREATED, CONSUMER_ALERT, ALERT_CLOSURE, DEVIATION_CLOSED }

enum class NotificationStatus { PUBLISHED, FAILED }

// A deviation signal that passed contract validation. Only ever built by the intake validation,
// so the workflow and Activities can trust its fields.
data class FullServeSignal(
    val orderId: String,
    val source: SignalSource,
    val serviceType: String,
    val market: String,
    val deviationType: String,
    val reasonCode: String,
    val occurrence: String,
    val reasonCaused: String?
)

// A resolution update that passed contract validation. deviationId is optional: when the
// publisher supplies it, an update meant for an earlier (already closed) deviation of the same
// order is recognised as stale instead of closing the current one.
data class FullServeUpdate(
    val orderId: String,
    val source: SignalSource,
    val trigger: ClosureTrigger,
    val resolutionStatus: ResolutionStatus,
    val deviationId: String?,
    val note: String?
)

data class FullServeDeviation(
    val deviationId: String,
    val workflowId: String,
    val runId: String,
    val signal: FullServeSignal,
    val status: FullServeStatus,
    val businessRule: String?,
    val closureTrigger: ClosureTrigger?,
    val closureSource: SignalSource?,
    val closureNote: String?,
    val createdAt: Instant,
    val closedAt: Instant?
) {
    val orderId: String get() = signal.orderId

    companion object {
        fun open(deviationId: String, workflowId: String, runId: String, signal: FullServeSignal, now: Instant) =
            FullServeDeviation(
                deviationId, workflowId, runId, signal, FullServeStatus.OPEN,
                businessRule = null, closureTrigger = null, closureSource = null, closureNote = null,
                createdAt = now, closedAt = null
            )
    }
}

// One outbound publication (O360 or consumer). notificationKey is deterministic, so a retried
// Activity finds the earlier PUBLISHED row and does not publish again.
data class FullServeNotification(
    val notificationKey: String,
    val deviationId: String,
    val kind: NotificationKind,
    val recipient: String,
    val destination: String,
    val status: NotificationStatus,
    val detail: String?,
    val recordedAt: Instant
) {
    companion object {
        fun keyFor(deviationId: String, kind: NotificationKind, recipient: String, alertNumber: Int? = null) =
            listOfNotNull(deviationId, kind.name, recipient, alertNumber?.toString()).joinToString(":")
    }
}
