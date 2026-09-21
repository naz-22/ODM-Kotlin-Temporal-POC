package com.example.payment.adapters.input.web.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class ChargeRequest(
    @field:NotBlank
    val orderId: String,

    @field:NotBlank
    val customerId: String,

    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal,

    @field:Min(1)
    val attemptNumber: Int
)

data class ChargeResponse(
    val succeeded: Boolean,
    val failureReason: String?
)
