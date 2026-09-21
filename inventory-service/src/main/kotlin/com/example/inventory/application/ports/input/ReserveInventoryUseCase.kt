package com.example.inventory.application.ports.input

enum class ReservationOutcome { RESERVED, UNAVAILABLE }

interface ReserveInventoryUseCase {

    fun reserve(orderId: String, productId: String, quantity: Int): ReservationOutcome

    fun release(orderId: String)
}
