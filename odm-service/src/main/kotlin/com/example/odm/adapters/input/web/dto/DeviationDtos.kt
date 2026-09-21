package com.example.odm.adapters.input.web.dto

import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationType
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class DecisionRequest(
    @field:NotBlank
    val orderId: String,

    val type: DeviationType,

    @field:Min(0)
    val attemptNumber: Int,

    @field:NotBlank
    val reason: String
)

data class DecisionResponse(val decision: String)

data class DeviationResponse(
    val id: UUID,
    val orderId: String,
    val type: DeviationType,
    val attemptNumber: Int,
    val reason: String,
    val status: String,
    val decision: String?,
    val createdAt: Instant,
    val resolvedAt: Instant?
) {
    companion object {
        fun from(deviation: Deviation) = DeviationResponse(
            id = deviation.id,
            orderId = deviation.orderId,
            type = deviation.type,
            attemptNumber = deviation.attemptNumber,
            reason = deviation.reason,
            status = deviation.status.name,
            decision = deviation.decision?.name,
            createdAt = deviation.createdAt,
            resolvedAt = deviation.resolvedAt
        )
    }
}
