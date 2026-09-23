package com.example.odm.domain.model

import java.time.Instant

// Business event: an ODM recovery deviation reached its terminal RESOLVED state.
// Published to Solace for downstream consumers; never used to drive the recovery itself.
data class DeviationResolvedEvent(
    val eventType: String,
    // Deterministic: a deviation is resolved exactly once, so every (re)publication of that
    // resolution carries the same id and downstream consumers can de-duplicate on it.
    val eventId: String,
    val deviationKey: String,
    val orderId: String,
    val deviationType: RecoveryDeviationType,
    val resolution: RecoveryResolution,
    val resolvedBy: AlertOwner,
    val resolvedAt: Instant
) {
    companion object {
        const val EVENT_TYPE = "DeviationResolved"

        fun eventIdFor(deviationKey: String) = "deviation-resolved-$deviationKey"

        fun from(deviation: RecoveryDeviation): DeviationResolvedEvent {
            require(deviation.status == RecoveryStatus.RESOLVED) { "${deviation.deviationKey} is not RESOLVED" }
            return DeviationResolvedEvent(
                eventType = EVENT_TYPE,
                eventId = eventIdFor(deviation.deviationKey),
                deviationKey = deviation.deviationKey,
                orderId = deviation.orderId,
                deviationType = deviation.type,
                resolution = requireNotNull(deviation.resolution),
                // Who resolved it, as the team that owns the alert: automation or RCMP.
                resolvedBy = when (deviation.resolution) {
                    RecoveryResolution.AUTO_RECOVERED -> AlertOwner.AUTORECOVERY
                    RecoveryResolution.MANUALLY_RESOLVED -> AlertOwner.RCMP
                },
                resolvedAt = requireNotNull(deviation.resolvedAt)
            )
        }
    }
}
