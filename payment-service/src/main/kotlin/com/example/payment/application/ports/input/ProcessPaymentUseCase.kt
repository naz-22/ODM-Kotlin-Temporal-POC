package com.example.payment.application.ports.input

import java.math.BigDecimal

data class PaymentOutcome(val succeeded: Boolean, val failureReason: String?)

interface ProcessPaymentUseCase {

    fun charge(orderId: String, customerId: String, amount: BigDecimal, attemptNumber: Int): PaymentOutcome
}
