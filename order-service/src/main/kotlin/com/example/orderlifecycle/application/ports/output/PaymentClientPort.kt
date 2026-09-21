package com.example.orderlifecycle.application.ports.output

import java.math.BigDecimal

// Output port toward payment-service.
interface PaymentClientPort {

    fun charge(
        orderId: String,
        customerId: String,
        amount: BigDecimal,
        attemptNumber: Int
    ): PaymentAttemptResult
}
