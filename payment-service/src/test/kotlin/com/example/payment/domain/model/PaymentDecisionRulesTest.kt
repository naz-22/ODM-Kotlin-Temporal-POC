package com.example.payment.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PaymentDecisionRulesTest {

    @Test
    fun `default customer always succeeds`() {
        val (status, reason) = PaymentDecisionRules.decide("customer-1", 1)
        assertEquals(PaymentAttemptStatus.SUCCEEDED, status)
        assertEquals(null, reason)
    }

    @Test
    fun `cust-payment-fail-always fails on every attempt`() {
        for (attempt in 1..5) {
            val (status, _) = PaymentDecisionRules.decide("cust-payment-fail-always", attempt)
            assertEquals(PaymentAttemptStatus.FAILED, status, "attempt $attempt should fail")
        }
    }

    @Test
    fun `cust-payment-fail-once fails only on the first attempt`() {
        val (firstStatus, _) = PaymentDecisionRules.decide("cust-payment-fail-once", 1)
        assertEquals(PaymentAttemptStatus.FAILED, firstStatus)

        val (secondStatus, secondReason) = PaymentDecisionRules.decide("cust-payment-fail-once", 2)
        assertEquals(PaymentAttemptStatus.SUCCEEDED, secondStatus)
        assertEquals(null, secondReason)
    }
}
