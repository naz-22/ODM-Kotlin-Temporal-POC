package com.example.inventory.adapters.output.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "inventory_items")
class InventoryItemEntity(

    @Id
    @Column(name = "product_id")
    val productId: String,

    @Column(nullable = false)
    var availableStock: Int
)
