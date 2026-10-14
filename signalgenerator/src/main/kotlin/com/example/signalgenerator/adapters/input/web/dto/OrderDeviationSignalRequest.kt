package com.example.signalgenerator.adapters.input.web.dto

import com.example.signalgenerator.domain.OrderDeviationSignal
import jakarta.validation.constraints.NotBlank

data class OrderDeviationSignalRequest(
    @field:NotBlank val orderId: String,
    @field:NotBlank val source: String,
    @field:NotBlank val serviceType: String,
    @field:NotBlank val market: String,
    @field:NotBlank val deviationType: String,
    @field:NotBlank val reasonCode: String,
    @field:NotBlank val occurrence: String,
    @field:NotBlank val reasonCaused: String
) {
    fun toDomain() = OrderDeviationSignal(
        orderId, source, serviceType, market, deviationType, reasonCode, occurrence, reasonCaused
    )
}
