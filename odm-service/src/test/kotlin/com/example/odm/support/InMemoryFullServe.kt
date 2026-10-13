package com.example.odm.support

import com.example.odm.application.ports.output.FullServeDeviationRepository
import com.example.odm.application.ports.output.FullServePublisherPort
import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeNotification
import com.example.odm.domain.model.FullServeStatus
import com.example.odm.domain.model.FullServeUpdate
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Test double for the Full-Serve persistence port. insertIfAbsent enforces the same rules as the
// database: unique deviation_id, and at most one OPEN deviation per order.
class InMemoryFullServeRepository : FullServeDeviationRepository {
    val deviations = ConcurrentHashMap<String, FullServeDeviation>()
    val notifications = ConcurrentHashMap<String, FullServeNotification>()

    override fun findById(deviationId: String) = deviations[deviationId]
    override fun findOpenByOrderId(orderId: String) =
        deviations.values.firstOrNull { it.orderId == orderId && it.status == FullServeStatus.OPEN }
    override fun findByOrderId(orderId: String) = deviations.values.filter { it.orderId == orderId }.sortedBy { it.createdAt }

    @Synchronized
    override fun insertIfAbsent(deviation: FullServeDeviation): Boolean {
        if (deviations.containsKey(deviation.deviationId) || findOpenByOrderId(deviation.orderId) != null) return false
        deviations[deviation.deviationId] = deviation
        return true
    }

    override fun recordBusinessRule(deviationId: String, businessRule: String) {
        deviations.computeIfPresent(deviationId) { _, d -> d.copy(businessRule = businessRule) }
    }

    override fun closeIfOpen(deviationId: String, update: FullServeUpdate, at: Instant): Boolean {
        var closed = false
        deviations.computeIfPresent(deviationId) { _, d ->
            if (d.status != FullServeStatus.OPEN) d
            else d.copy(status = FullServeStatus.CLOSED, closureTrigger = update.trigger, closureSource = update.source,
                closureNote = update.note, closedAt = at).also { closed = true }
        }
        return closed
    }

    override fun findNotification(notificationKey: String) = notifications[notificationKey]
    override fun findNotifications(deviationId: String) = notifications.values.filter { it.deviationId == deviationId }
    override fun saveNotification(notification: FullServeNotification) {
        notifications[notification.notificationKey] = notification
    }
}

// Records every publication. failNextPublishes simulates a brief broker outage; destinations
// containing any of failingDestinations always fail (an outage that outlasts the retries).
class RecordingFullServePublisher : FullServePublisherPort {
    data class Published(val destination: String, val payload: Map<String, Any?>)

    val published: MutableList<Published> = Collections.synchronizedList(mutableListOf())
    val attempts = AtomicInteger()
    private val remainingFailures = AtomicInteger()
    val failingDestinations: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun failNextPublishes(count: Int) = remainingFailures.set(count)

    override fun publish(destination: String, payload: Map<String, Any?>) {
        attempts.incrementAndGet()
        if (failingDestinations.any { destination.contains(it) }) throw IllegalStateException("Solace unavailable for $destination (simulated)")
        if (remainingFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw IllegalStateException("Solace unavailable (simulated)")
        published += Published(destination, payload)
    }

    fun to(destinationPrefix: String) = synchronized(published) { published.filter { it.destination.startsWith(destinationPrefix) } }
}
