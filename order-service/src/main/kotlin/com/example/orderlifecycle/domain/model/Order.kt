package com.example.orderlifecycle.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// data class = immutable data holder, roughly similar to a Java value/DTO class.
data class Order(
    val id: UUID,
    val customerId: String,
    val productId: String,
    val quantity: Int,
    val amount: BigDecimal,
    val status: OrderStatus,
    val createdAt: Instant
) {

    fun moveTo(newStatus: OrderStatus): Order {
        // Protect the order from invalid lifecycle transitions.
        if (!status.canTransitionTo(newStatus)) {
            throw IllegalStateException(
                "Invalid order status transition: $status -> $newStatus"
            )
        }

        return copy(status = newStatus)
    }
}

enum class OrderStatus {

    ORDER_PLACED,
    VALIDATING,
    INVENTORY_RESERVATION_PENDING,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    ORDER_CONFIRMED,
    NOTIFICATION_PENDING,
    COMPLETED,
    ORDER_FAILED,
    ORDER_CANCELLED;

    fun canTransitionTo(newStatus: OrderStatus): Boolean {
        return when (this) {
            ORDER_PLACED -> newStatus == VALIDATING
            VALIDATING -> newStatus == INVENTORY_RESERVATION_PENDING
            INVENTORY_RESERVATION_PENDING ->
                newStatus == INVENTORY_RESERVED || newStatus == ORDER_FAILED
            INVENTORY_RESERVED -> newStatus == PAYMENT_PENDING
            PAYMENT_PENDING ->
                // COMPENSATE decisions cancel the order; a FAIL_ORDER decision from
                // ODM/Drools (defensive - the shipped rules only ever return RETRY or
                // COMPENSATE for a payment deviation) also has to be a legal move.
                newStatus == PAYMENT_CONFIRMED || newStatus == ORDER_CANCELLED || newStatus == ORDER_FAILED
            PAYMENT_CONFIRMED -> newStatus == ORDER_CONFIRMED
            ORDER_CONFIRMED -> newStatus == NOTIFICATION_PENDING
            NOTIFICATION_PENDING -> newStatus == COMPLETED

            ORDER_FAILED,
            ORDER_CANCELLED,
            COMPLETED -> false
        }
    }
}
