package com.example.odm.adapters.input.web.dto

import com.example.odm.application.ports.input.RecoveryRecord
import com.example.odm.domain.model.DeviationSignal
import com.example.odm.domain.model.RecoveryAlert
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.ReleaseStage
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

// Simulated upstream deviation signal. Quantities/itemId are only required for SHORT_PICK
// (enforced by DeviationSignal's own invariants).
data class DeviationSignalRequest(
    @field:NotBlank val deviationKey: String,
    @field:NotBlank val orderId: String,
    val type: RecoveryDeviationType,
    val releaseStage: ReleaseStage,
    @field:NotBlank val market: String,
    val itemId: String? = null,
    val requestedQuantity: Int? = null,
    val pickedQuantity: Int? = null,
    @field:NotBlank val reason: String
) {
    fun toSignal() = DeviationSignal(
        deviationKey, orderId, type, releaseStage, market.uppercase(), itemId, requestedQuantity, pickedQuantity, reason
    )
}

data class DeviationSignalResponse(
    val deviationKey: String,
    val duplicate: Boolean,
    val decision: String,
    val decisionRule: String,
    val status: String,
    val workflowId: String
)

data class ManualResolutionRequest(
    @field:NotBlank val resolvedBy: String,
    @field:NotBlank val note: String
)

data class RecoveryAlertResponse(
    val id: UUID,
    val status: String,
    val owner: String,
    val reason: String,
    val openedAt: Instant,
    val closedAt: Instant?
) {
    companion object {
        fun from(a: RecoveryAlert) = RecoveryAlertResponse(a.id, a.status.name, a.owner.name, a.reason, a.openedAt, a.closedAt)
    }
}

data class RecoveryResponse(
    val deviationKey: String,
    val orderId: String,
    val type: String,
    val releaseStage: String,
    val market: String,
    val itemId: String?,
    val requestedQuantity: Int?,
    val pickedQuantity: Int?,
    val reason: String,
    val autoRecoveryEnabled: Boolean,
    val decision: String,
    val decisionRule: String,
    val status: String,
    val resolution: String?,
    val resolutionNote: String?,
    val workflowId: String,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val alert: RecoveryAlertResponse?
) {
    companion object {
        fun from(record: RecoveryRecord): RecoveryResponse {
            val d: RecoveryDeviation = record.deviation
            return RecoveryResponse(
                d.deviationKey, d.orderId, d.type.name, d.releaseStage.name, d.market, d.itemId,
                d.requestedQuantity, d.pickedQuantity, d.reason, d.autoRecoveryEnabled, d.decision.name,
                d.decisionRule, d.status.name, d.resolution?.name, d.resolutionNote, d.workflowId,
                d.createdAt, d.resolvedAt, record.alert?.let(RecoveryAlertResponse::from)
            )
        }
    }
}
