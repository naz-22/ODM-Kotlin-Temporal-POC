package com.example.notification.domain

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class NotificationRecord(
    val orderId: String,
    val customerId: String,
    val message: String,
    val receivedAt: Instant
)

// No database for this service, per the brief: a real Solace consumer is the point
// of the demo, not where the notification result ends up living. An in-memory store
// is explicitly acceptable here.
@Component
class NotificationStore {

    private val notifications = ConcurrentHashMap<String, NotificationRecord>()

    fun record(notification: NotificationRecord) {
        notifications[notification.orderId] = notification
    }

    fun findByOrderId(orderId: String): NotificationRecord? = notifications[orderId]

    fun all(): List<NotificationRecord> = notifications.values.toList()
}
