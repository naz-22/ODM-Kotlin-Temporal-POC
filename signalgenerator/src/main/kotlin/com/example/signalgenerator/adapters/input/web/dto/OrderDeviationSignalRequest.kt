package com.example.signalgenerator.adapters.input.web.dto

import com.example.signalgenerator.domain.OrderDeviationSignal
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class OrderDeviationSignalRequest(
    @field:NotBlank val deviationKey: String,
    @field:NotBlank val orderId: String,
    @field:NotBlank val type: String,
    @field:NotBlank val releaseStage: String,
    @field:NotBlank val market: String,
    @field:NotBlank val itemId: String,
    @field:Min(0) val requestedQuantity: Int,
    @field:Min(0) val pickedQuantity: Int,
    @field:NotBlank val reason: String
) {
    fun toDomain() = OrderDeviationSignal(
        deviationKey, orderId, type, releaseStage, market, itemId,
        requestedQuantity, pickedQuantity, reason
    )
}
