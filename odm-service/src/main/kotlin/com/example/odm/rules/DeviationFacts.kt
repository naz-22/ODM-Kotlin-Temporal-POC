package com.example.odm.rules

// Drools Facts. Deliberately plain, small classes with the exact business data a
// rule needs to reason about - nothing about persistence, REST, or Solace leaks in
// here. Kotlin generates the JavaBean getters (getOrderId(), getAttemptNumber(), ...)
// that Drools' pattern matching relies on.

class InventoryUnavailableDeviationFact(
    val orderId: String,
    val reason: String
)

class PaymentFailedDeviationFact(
    val orderId: String,
    val attemptNumber: Int,
    val maximumAttempts: Int,
    val failureReason: String
)

// A rule's "then" writes its verdict into this global rather than returning a
// value, which is the normal way Drools rules communicate a result back out.
class DecisionHolder {
    var decision: String? = null
}
