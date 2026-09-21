package com.example.payment.application.ports.output

import com.example.payment.domain.model.PaymentAttempt

interface PaymentAttemptRepository {

    // orderId + attemptNumber is the idempotency key: retrying the same attempt
    // (e.g. a Temporal Activity technical retry) must not charge twice.
    fun findByOrderIdAndAttemptNumber(orderId: String, attemptNumber: Int): PaymentAttempt?

    fun save(attempt: PaymentAttempt): PaymentAttempt
}
