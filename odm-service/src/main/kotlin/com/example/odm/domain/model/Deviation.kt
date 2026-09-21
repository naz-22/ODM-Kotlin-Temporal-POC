package com.example.odm.domain.model

import java.time.Instant
import java.util.UUID

// The three deviation types this POC's architecture defines. In practice only
// INVENTORY_UNAVAILABLE and PAYMENT_FAILED are ever the *stored* type of a
// deviation (they are what order-service's Activities actually report).
// PAYMENT_RETRY_EXHAUSTED is the situation a PAYMENT_FAILED deviation ends up in
// once Drools returns COMPENSATE (see status/decision below) - it is intentionally
// not a distinct stored type, so the idempotency key (orderId + type + attemptNumber)
// stays stable across repeated calls for the very same attempt. See docs/drools.md.
enum class DeviationType { INVENTORY_UNAVAILABLE, PAYMENT_FAILED, PAYMENT_RETRY_EXHAUSTED }

enum class DeviationStatus { OPEN, RESOLVED }

enum class Decision { RETRY, COMPENSATE, FAIL_ORDER }

data class Deviation(
    val id: UUID,
    val orderId: String,
    val type: DeviationType,
    val attemptNumber: Int,
    val reason: String,
    val status: DeviationStatus,
    val decision: Decision?,
    val createdAt: Instant,
    val resolvedAt: Instant?
) {

    fun resolvedWith(decision: Decision): Deviation =
        copy(status = DeviationStatus.RESOLVED, decision = decision, resolvedAt = Instant.now())
}
