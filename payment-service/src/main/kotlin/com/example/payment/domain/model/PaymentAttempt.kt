package com.example.payment.domain.model

import java.math.BigDecimal
import java.time.Instant

enum class PaymentAttemptStatus { SUCCEEDED, FAILED }

data class PaymentAttempt(
    val orderId: String,
    val attemptNumber: Int,
    val customerId: String,
    val amount: BigDecimal,
    val status: PaymentAttemptStatus,
    val failureReason: String?,
    val createdAt: Instant
)

// Deterministic demo behaviour, deliberately simple and readable: this is the one
// place that decides whether a given attempt succeeds, driven only by customerId
// so the four demo scenarios in the README are 100% reproducible.
object PaymentDecisionRules {

    private const val ALWAYS_FAIL_CUSTOMER = "cust-payment-fail-always"
    private const val FAIL_ONCE_CUSTOMER = "cust-payment-fail-once"

    fun decide(customerId: String, attemptNumber: Int): Pair<PaymentAttemptStatus, String?> {
        return when {
            customerId == ALWAYS_FAIL_CUSTOMER ->
                PaymentAttemptStatus.FAILED to "Payment declined for customer $customerId"

            customerId == FAIL_ONCE_CUSTOMER && attemptNumber == 1 ->
                PaymentAttemptStatus.FAILED to "Payment declined on first attempt for customer $customerId"

            else -> PaymentAttemptStatus.SUCCEEDED to null
        }
    }
}
