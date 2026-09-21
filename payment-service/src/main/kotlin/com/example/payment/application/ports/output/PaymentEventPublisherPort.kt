package com.example.payment.application.ports.output

import java.math.BigDecimal

interface PaymentEventPublisherPort {

    fun publishPaymentSucceeded(orderId: String, amount: BigDecimal)

    fun publishPaymentFailed(orderId: String, attemptNumber: Int, reason: String)
}
