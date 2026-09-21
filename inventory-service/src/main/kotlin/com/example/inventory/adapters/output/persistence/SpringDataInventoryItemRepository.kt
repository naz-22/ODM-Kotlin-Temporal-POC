package com.example.inventory.adapters.output.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SpringDataInventoryItemRepository : JpaRepository<InventoryItemEntity, String>
