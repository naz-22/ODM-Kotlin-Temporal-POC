package com.example.odm.application.ports.output

import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeNotification
import com.example.odm.domain.model.FullServeUpdate
import java.time.Instant

interface FullServeDeviationRepository {

    fun findById(deviationId: String): FullServeDeviation?

    fun findOpenByOrderId(orderId: String): FullServeDeviation?

    fun findByOrderId(orderId: String): List<FullServeDeviation>

    // Race-safe create. The database allows at most one OPEN deviation per order (partial unique
    // index), so two concurrent creates for the same order can never both succeed. Returns true
    // if this call inserted the row, false if any conflicting row already existed.
    fun insertIfAbsent(deviation: FullServeDeviation): Boolean

    fun recordBusinessRule(deviationId: String, businessRule: String)

    // OPEN -> CLOSED only; returns false if the deviation was already closed.
    fun closeIfOpen(deviationId: String, update: FullServeUpdate, at: Instant): Boolean

    fun findNotification(notificationKey: String): FullServeNotification?

    fun findNotifications(deviationId: String): List<FullServeNotification>

    // Inserts or replaces the outcome recorded for this notification key.
    fun saveNotification(notification: FullServeNotification)
}
