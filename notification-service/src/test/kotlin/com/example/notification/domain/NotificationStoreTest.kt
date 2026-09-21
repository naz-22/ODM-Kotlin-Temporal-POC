package com.example.notification.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationStoreTest {

    @Test
    fun `records and retrieves a notification by orderId`() {
        val store = NotificationStore()
        val record = NotificationRecord("order-1", "customer-1", "Your order order-1 has been confirmed.", Instant.now())

        store.record(record)

        assertEquals(record, store.findByOrderId("order-1"))
    }

    @Test
    fun `returns null for an order that has no notification yet`() {
        val store = NotificationStore()
        assertNull(store.findByOrderId("unknown-order"))
    }

    @Test
    fun `a later notification for the same order replaces the earlier one`() {
        val store = NotificationStore()
        store.record(NotificationRecord("order-2", "customer-1", "first", Instant.now()))
        store.record(NotificationRecord("order-2", "customer-1", "second", Instant.now()))

        assertEquals("second", store.findByOrderId("order-2")?.message)
        assertEquals(1, store.all().size)
    }
}
