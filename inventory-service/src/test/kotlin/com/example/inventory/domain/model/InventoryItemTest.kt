package com.example.inventory.domain.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class InventoryItemTest {

    @Test
    fun `reserve decrements available stock when enough is available`() {
        val item = InventoryItem("PROD-1", 100)
        val reserved = item.reserve(10)
        assertEquals(90, reserved.availableStock)
    }

    @Test
    fun `reserve fails when stock is insufficient`() {
        val item = InventoryItem("PROD-OUT-OF-STOCK", 0)
        assertFailsWith<IllegalStateException> { item.reserve(1) }
    }

    @Test
    fun `release restores previously reserved stock`() {
        val item = InventoryItem("PROD-1", 90)
        val released = item.release(10)
        assertEquals(100, released.availableStock)
    }

    @Test
    fun `canFulfil reflects available stock exactly`() {
        val item = InventoryItem("PROD-2", 50)
        assert(item.canFulfil(50))
        assert(!item.canFulfil(51))
    }
}
