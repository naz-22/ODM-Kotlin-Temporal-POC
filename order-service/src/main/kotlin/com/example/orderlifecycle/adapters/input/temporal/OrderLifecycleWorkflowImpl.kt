package com.example.orderlifecycle.adapters.input.temporal

import com.example.orderlifecycle.application.ports.input.OrderLifecycleWorkflow
import com.example.orderlifecycle.application.ports.output.DeviationDecision
import com.example.orderlifecycle.application.ports.output.InventoryReservationResult
import com.example.orderlifecycle.application.ports.output.OrderLifecycleActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

// This class is the durable orchestrator for the order lifecycle. It MUST stay
// deterministic: no JPA, no HTTP, no Spring beans, no Solace here - only calls to
// Activities (which do that real work) and plain workflow logic/looping.
//
// There are two different kinds of "retry" at play, and it is important not to
// confuse them:
//  - Temporal's RetryOptions below are TECHNICAL retries: they fire when an Activity
//    throws (network blip, the downstream service being briefly unreachable, a 5xx).
//  - The RETRY / COMPENSATE / FAIL_ORDER decisions coming back from ODM/Drools are
//    BUSINESS retries: the call to payment-service succeeded technically and came
//    back with a normal "payment declined" answer; whether to try again is a business
//    decision, not something Temporal itself understands.
class OrderLifecycleWorkflowImpl : OrderLifecycleWorkflow {

    private val defaultRetry = RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setBackoffCoefficient(2.0)
        .setMaximumInterval(Duration.ofSeconds(10))
        .setMaximumAttempts(5) // technical retry ceiling - infrastructure failures only
        .build()

    private val activities = Workflow.newActivityStub(
        OrderLifecycleActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(defaultRetry)
            .build()
    )
    // Activity Stub = local proxy used by the Workflow to invoke an Activity.

    // Safety cap only: stops an infinite loop if ODM/Drools misbehaves. The real
    // stopping condition is always the RETRY/COMPENSATE/FAIL_ORDER decision itself.
    private val maxPaymentAttemptsSafetyCap = 10

    override fun startOrder(orderId: String): String {

        activities.validateOrder(orderId)

        val reservation = activities.reserveInventory(orderId)
        if (reservation == InventoryReservationResult.UNAVAILABLE) {
            val decision = activities.getDeviationDecision(
                orderId, "INVENTORY_UNAVAILABLE", 0, "Inventory unavailable for order"
            )
            // Inventory unavailability only ever resolves to FAIL_ORDER (see docs/drools.md),
            // but the workflow simply obeys whatever ODM/Drools decides.
            activities.failOrder(orderId)
            return "ORDER_FAILED"
        }

        var attemptNumber = 1
        while (attemptNumber <= maxPaymentAttemptsSafetyCap) {
            val paymentResult = activities.processPayment(orderId, attemptNumber)

            if (paymentResult.succeeded) {
                activities.confirmOrder(orderId)
                activities.completeOrder(orderId)
                return "COMPLETED"
            }

            val decision = activities.getDeviationDecision(
                orderId,
                "PAYMENT_FAILED",
                attemptNumber,
                paymentResult.failureReason ?: "Payment failed"
            )

            when (decision) {
                DeviationDecision.RETRY -> {
                    attemptNumber += 1
                    // loop again - business retry
                }
                DeviationDecision.COMPENSATE -> {
                    activities.releaseInventory(orderId)
                    activities.cancelOrder(orderId)
                    return "ORDER_CANCELLED"
                }
                DeviationDecision.FAIL_ORDER -> {
                    activities.releaseInventory(orderId)
                    activities.failOrder(orderId)
                    return "ORDER_FAILED"
                }
            }
        }

        // Safety net: ODM kept returning RETRY past the sanity cap.
        activities.releaseInventory(orderId)
        activities.cancelOrder(orderId)
        return "ORDER_CANCELLED"
    }
}
