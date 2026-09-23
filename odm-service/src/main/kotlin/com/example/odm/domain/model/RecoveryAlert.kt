package com.example.odm.domain.model

import java.time.Instant
import java.util.UUID

enum class AlertStatus { OPEN, CLOSED }

// Who is expected to act on an open alert: the automated recovery itself, or RCMP
// (the manual resolution team) once automation is skipped or has given up.
enum class AlertOwner { AUTORECOVERY, RCMP }

// One alert per recovery deviation (enforced by a unique constraint on deviation_key).
data class RecoveryAlert(
    val id: UUID,
    val deviationKey: String,
    val status: AlertStatus,
    val owner: AlertOwner,
    val reason: String,
    val openedAt: Instant,
    val closedAt: Instant?
) {

    fun reassignedTo(newOwner: AlertOwner, newReason: String): RecoveryAlert {
        check(status == AlertStatus.OPEN) { "Cannot reassign a closed alert for $deviationKey" }
        return copy(owner = newOwner, reason = newReason)
    }

    // Same owner, new explanation - e.g. RCMP is told a restarted workflow handed the case back.
    fun withReason(newReason: String): RecoveryAlert {
        check(status == AlertStatus.OPEN) { "Cannot change the reason of a closed alert for $deviationKey" }
        return copy(reason = newReason)
    }

    fun closed(at: Instant): RecoveryAlert = copy(status = AlertStatus.CLOSED, closedAt = at)

    companion object {
        fun open(deviationKey: String, owner: AlertOwner, reason: String, now: Instant) = RecoveryAlert(
            id = UUID.randomUUID(),
            deviationKey = deviationKey,
            status = AlertStatus.OPEN,
            owner = owner,
            reason = reason,
            openedAt = now,
            closedAt = null
        )
    }
}
