package com.example.odm.domain.model

import java.time.Instant

// Autorecovery model (Scenarios 5/6). Deliberately separate from the existing
// Deviation/DeviationType model, which stays exactly as the order lifecycle uses it.

// enum class = a closed, type-safe set of named constants (like a Java enum).

// Where the order is in fulfilment when the upstream deviation happened. PRE_RELEASE
// means the order has not been released to the last mile yet, so it can still be
// re-planned (rebooked); POST_RELEASE means it has already left, so it cannot.
enum class ReleaseStage {
    PRE_RELEASE,
    POST_RELEASE;

    // Hard business invariant, NOT configurable policy: once released, an order is never
    // automatically recovered/rebooked. Drools decides policy only within this boundary.
    val allowsAutoRecovery: Boolean
        get() = this == PRE_RELEASE
}

enum class RecoveryDeviationType { SHORT_PICK, PROVIDED_SERVICE_FAILURE }

enum class RecoveryDecision { AUTO_RECOVER, MANUAL }

enum class RecoveryResolution { AUTO_RECOVERED, MANUALLY_RESOLVED }

enum class RecoveryStatus {
    RECEIVED,
    AUTO_RECOVERING,
    AWAITING_MANUAL_RESOLUTION,
    RESOLVED;

    // Lifecycle correctness lives here in Kotlin, not in Drools: which status may
    // follow which is a structural invariant, not configurable business policy.
    fun canTransitionTo(next: RecoveryStatus): Boolean = when (this) {
        RECEIVED -> next == AUTO_RECOVERING || next == AWAITING_MANUAL_RESOLUTION
        AUTO_RECOVERING -> next == AWAITING_MANUAL_RESOLUTION || next == RESOLVED
        AWAITING_MANUAL_RESOLUTION -> next == RESOLVED
        RESOLVED -> false
    }
}

// What the upstream OE/fulfilment system tells ODM happened. data class = Kotlin
// generates equals/hashCode/toString/copy from the constructor properties.
data class DeviationSignal(
    val deviationKey: String,
    val orderId: String,
    val type: RecoveryDeviationType,
    val releaseStage: ReleaseStage,
    val market: String,
    val itemId: String?,
    val requestedQuantity: Int?,
    val pickedQuantity: Int?,
    val reason: String
) {
    // init runs as part of construction: an invalid signal can never exist.
    init {
        require(deviationKey.isNotBlank()) { "deviationKey must not be blank" }
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(market.isNotBlank()) { "market must not be blank" }
        if (type == RecoveryDeviationType.SHORT_PICK) {
            require(!itemId.isNullOrBlank()) { "SHORT_PICK requires itemId" }
            require(requestedQuantity != null && requestedQuantity > 0) { "SHORT_PICK requires requestedQuantity > 0" }
            require(pickedQuantity != null && pickedQuantity >= 0) { "SHORT_PICK requires pickedQuantity >= 0" }
            require(pickedQuantity < requestedQuantity) {
                "Not a short pick: pickedQuantity ($pickedQuantity) must be less than requestedQuantity ($requestedQuantity)"
            }
        }
    }
}

data class RecoveryDeviation(
    val deviationKey: String,
    val orderId: String,
    val type: RecoveryDeviationType,
    val releaseStage: ReleaseStage,
    val market: String,
    val itemId: String?,
    val requestedQuantity: Int?,
    val pickedQuantity: Int?,
    val reason: String,
    val autoRecoveryEnabled: Boolean,
    val decision: RecoveryDecision,
    val decisionRule: String,
    val status: RecoveryStatus,
    val resolution: RecoveryResolution?,
    val resolutionNote: String?,
    val workflowId: String,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    // How many times the reconciler restarted a workflow run that ended without success.
    val workflowRestarts: Int = 0
) {

    init {
        require(decision != RecoveryDecision.AUTO_RECOVER || releaseStage.allowsAutoRecovery) {
            "$deviationKey: $releaseStage deviations can never be AUTO_RECOVER"
        }
    }

    // Units missing from the pick - what an alternative fulfilment option must cover.
    val shortQuantity: Int
        get() = (requestedQuantity ?: 0) - (pickedQuantity ?: 0)

    fun moveTo(next: RecoveryStatus): RecoveryDeviation {
        check(status.canTransitionTo(next)) { "Illegal recovery transition $status -> $next for $deviationKey" }
        return copy(status = next)
    }

    fun resolved(resolution: RecoveryResolution, note: String, at: Instant): RecoveryDeviation =
        moveTo(RecoveryStatus.RESOLVED).copy(resolution = resolution, resolutionNote = note, resolvedAt = at)

    // companion object = members that belong to the class itself (Java "static").
    companion object {
        fun received(
            signal: DeviationSignal,
            autoRecoveryEnabled: Boolean,
            decision: RecoveryDecision,
            decisionRule: String,
            workflowId: String,
            now: Instant
        ) = RecoveryDeviation(
            deviationKey = signal.deviationKey,
            orderId = signal.orderId,
            type = signal.type,
            releaseStage = signal.releaseStage,
            market = signal.market,
            itemId = signal.itemId,
            requestedQuantity = signal.requestedQuantity,
            pickedQuantity = signal.pickedQuantity,
            reason = signal.reason,
            autoRecoveryEnabled = autoRecoveryEnabled,
            decision = decision,
            decisionRule = decisionRule,
            status = RecoveryStatus.RECEIVED,
            resolution = null,
            resolutionNote = null,
            workflowId = workflowId,
            createdAt = now,
            resolvedAt = null
        )
    }
}
