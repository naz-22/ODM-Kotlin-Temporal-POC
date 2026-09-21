package com.example.orderlifecycle.application.ports.output

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

// Results returned by Activities. Kept as simple data/enum types so Temporal's
// default Jackson payload converter can serialize them without extra setup.

enum class InventoryReservationResult { RESERVED, UNAVAILABLE }

data class PaymentAttemptResult(
    val succeeded: Boolean,
    val failureReason: String?
)

enum class DeviationDecision { RETRY, COMPENSATE, FAIL_ORDER }

@ActivityInterface // Defines the contract for work executed by a Temporal Activity.
interface OrderLifecycleActivities {

    @ActivityMethod // Marks this function as a Temporal Activity method.
    fun validateOrder(orderId: String)
    // ORDER_PLACED -> VALIDATING. Basic structural validation of the order.

    @ActivityMethod
    fun reserveInventory(orderId: String): InventoryReservationResult
    // VALIDATING -> INVENTORY_RESERVATION_PENDING, calls inventory-service, then
    // -> INVENTORY_RESERVED on success. Never throws for "out of stock"; that is a
    // legitimate business outcome, not a technical failure Temporal should retry.

    @ActivityMethod
    fun processPayment(orderId: String, attemptNumber: Int): PaymentAttemptResult
    // INVENTORY_RESERVED -> PAYMENT_PENDING (first attempt only), calls payment-service.
    // A declined/failed payment is a business outcome, returned rather than thrown.

    @ActivityMethod
    fun getDeviationDecision(
        orderId: String,
        deviationType: String,
        attemptNumber: Int,
        reason: String
    ): DeviationDecision
    // Calls the ODM synchronous decision endpoint (backed by Drools). This is the
    // ONLY place business retry/compensation/failure decisions are made.

    @ActivityMethod
    fun releaseInventory(orderId: String)
    // Calls inventory-service to release a previously reserved item (compensation).

    @ActivityMethod
    fun confirmOrder(orderId: String)
    // PAYMENT_CONFIRMED -> ORDER_CONFIRMED, publishes the OrderConfirmed business event.

    @ActivityMethod
    fun completeOrder(orderId: String)
    // ORDER_CONFIRMED -> NOTIFICATION_PENDING -> COMPLETED.

    @ActivityMethod
    fun cancelOrder(orderId: String)
    // PAYMENT_PENDING -> ORDER_CANCELLED, used after inventory compensation.

    @ActivityMethod
    fun failOrder(orderId: String)
    // Moves the order to ORDER_FAILED (inventory unavailable path).
}
