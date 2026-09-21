package com.example.inventory.domain.model

import java.time.Instant

enum class ReservationStatus { RESERVED, RELEASED }

// orderId is the natural idempotency key: one reservation row per order, ever.
data class Reservation(
    val orderId: String,
    val productId: String,
    val quantity: Int,
    val status: ReservationStatus,
    val createdAt: Instant
) {
    fun released(): Reservation = copy(status = ReservationStatus.RELEASED)
}
