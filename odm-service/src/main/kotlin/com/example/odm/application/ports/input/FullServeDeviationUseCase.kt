package com.example.odm.application.ports.input

import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeNotification

// Inbound deviation signal as received from OE/OEP, before validation. Every field is nullable so
// that a missing field is reported as a validation error rather than a deserialization crash.
data class FullServeSignalMessage(
    val orderId: String? = null,
    val source: String? = null,
    val serviceType: String? = null,
    val market: String? = null,
    val deviationType: String? = null,
    val reasonCode: String? = null,
    val occurrence: String? = null,
    val reasonCaused: String? = null
)

// Inbound deviation/order update from OE, OEP or O360, before validation.
data class FullServeUpdateMessage(
    val orderId: String? = null,
    val source: String? = null,
    val trigger: String? = null,
    val resolutionStatus: String? = null,
    val deviationId: String? = null,
    val note: String? = null
)

enum class IntakeOutcome {
    // Workflow started; it creates the OPEN deviation.
    ACCEPTED,
    // Failed contract validation: nothing created, nothing started. Redelivery cannot help.
    REJECTED_INVALID,
    // The order already has an OPEN deviation: signal ignored.
    IGNORED_DUPLICATE,
    // The order's previous deviation is still being opened or closed. Try again shortly.
    RETRY_LATER
}

data class IntakeResult(
    val outcome: IntakeOutcome,
    val orderId: String?,
    val workflowId: String? = null,
    val existingDeviationId: String? = null,
    val errors: List<String> = emptyList()
)

enum class UpdateOutcome {
    // Delivered as a Temporal Signal to the OPEN deviation's workflow.
    SIGNALLED,
    REJECTED_INVALID,
    // Unknown order, or its deviation is already closed (a late or duplicate update).
    NO_OPEN_DEVIATION,
    // The update names a deviationId that is not the order's OPEN deviation.
    STALE_DEVIATION_ID,
    // An OPEN row exists but its workflow is not running (e.g. terminated by an operator).
    WORKFLOW_NOT_RUNNING
}

data class UpdateResult(
    val outcome: UpdateOutcome,
    val orderId: String?,
    val deviationId: String? = null,
    val workflowId: String? = null,
    val errors: List<String> = emptyList()
)

data class FullServeDeviationView(
    val deviation: FullServeDeviation,
    val notifications: List<FullServeNotification>
)

// The ODM ingress for Full Serve. Called by the Solace queue listener and the demo REST controller.
interface FullServeDeviationUseCase {

    fun receiveSignal(message: FullServeSignalMessage): IntakeResult

    fun receiveUpdate(message: FullServeUpdateMessage): UpdateResult

    fun findDeviation(deviationId: String): FullServeDeviationView?

    fun findByOrderId(orderId: String): List<FullServeDeviationView>
}
