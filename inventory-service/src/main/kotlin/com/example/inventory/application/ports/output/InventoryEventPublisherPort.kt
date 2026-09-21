package com.example.inventory.application.ports.output

// Output port toward Solace. Only genuine business events are published here -
// never a "row changed" notification for its own sake.
interface InventoryEventPublisherPort {

    fun publishInventoryReserved(orderId: String, productId: String, quantity: Int)

    fun publishInventoryUnavailable(orderId: String, productId: String, quantity: Int)

    fun publishInventoryReleased(orderId: String, productId: String, quantity: Int)
}
