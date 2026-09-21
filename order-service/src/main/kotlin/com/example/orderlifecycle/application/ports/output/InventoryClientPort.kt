package com.example.orderlifecycle.application.ports.output

// Output port toward inventory-service. Implemented by a REST adapter and used
// only from inside Activity implementations - never from the Workflow itself.
interface InventoryClientPort {

    fun reserve(orderId: String, productId: String, quantity: Int): InventoryReservationResult

    fun release(orderId: String)
}
