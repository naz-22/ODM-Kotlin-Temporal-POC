package com.example.inventory.application.ports.output

import com.example.inventory.domain.model.InventoryItem

interface InventoryItemRepository {

    fun findByProductId(productId: String): InventoryItem?

    fun save(item: InventoryItem): InventoryItem
}
