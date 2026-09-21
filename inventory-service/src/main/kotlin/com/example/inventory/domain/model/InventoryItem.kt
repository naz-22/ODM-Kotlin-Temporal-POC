package com.example.inventory.domain.model

// data class = immutable data holder, roughly similar to a Java value/DTO class.
data class InventoryItem(
    val productId: String,
    val availableStock: Int
) {

    fun canFulfil(quantity: Int): Boolean = availableStock >= quantity

    fun reserve(quantity: Int): InventoryItem {
        check(canFulfil(quantity)) { "Not enough stock for $productId: have $availableStock, need $quantity" }
        return copy(availableStock = availableStock - quantity)
    }

    fun release(quantity: Int): InventoryItem = copy(availableStock = availableStock + quantity)
}
