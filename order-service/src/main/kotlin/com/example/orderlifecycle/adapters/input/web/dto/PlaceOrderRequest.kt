package com.example.orderlifecycle.adapters.input.web.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class PlaceOrderRequest(
    @field:NotBlank
    val customerId: String,
    // @field: tells Kotlin to put the validation annotation on the generated field.

    @field:NotBlank
    val productId: String,

    @field:Min(1)
    val quantity: Int,

    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal
)
