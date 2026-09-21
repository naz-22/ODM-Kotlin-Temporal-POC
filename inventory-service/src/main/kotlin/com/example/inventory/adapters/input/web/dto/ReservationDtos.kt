package com.example.inventory.adapters.input.web.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class ReserveInventoryRequest(
    @field:NotBlank
    val orderId: String,

    @field:NotBlank
    val productId: String,

    @field:Min(1)
    val quantity: Int
)

data class ReservationResponse(
    val status: String
)
