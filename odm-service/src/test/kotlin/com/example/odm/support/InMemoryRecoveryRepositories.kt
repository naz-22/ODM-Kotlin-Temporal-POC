package com.example.odm.support

import com.example.odm.application.ports.output.DeviationEventPublisherPort
import com.example.odm.application.ports.output.RecoveryAlertRepository
import com.example.odm.application.ports.output.RecoveryDeviationRepository
import com.example.odm.domain.model.DeviationResolvedEvent
import com.example.odm.domain.model.RecoveryAlert
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// Test doubles for the persistence ports. Thread-safe because Temporal Activity
// threads and the test thread both touch them in the end-to-end test.
class InMemoryRecoveryDeviationRepository : RecoveryDeviationRepository {
    val rows = ConcurrentHashMap<String, RecoveryDeviation>()
    override fun findByKey(deviationKey: String) = rows[deviationKey]
    override fun insertIfAbsent(deviation: RecoveryDeviation) = rows.putIfAbsent(deviation.deviationKey, deviation) == null
    override fun save(deviation: RecoveryDeviation) = deviation.also { rows[it.deviationKey] = it }
    override fun findUnresolvedCreatedBefore(cutoff: Instant) =
        rows.values.filter { it.status != RecoveryStatus.RESOLVED && it.createdAt.isBefore(cutoff) }

    // Same semantics as the conditional UPDATE: atomic per key, refuses at the limit.
    override fun tryRecordWorkflowRestart(deviationKey: String, maxRestarts: Int): Boolean {
        var recorded = false
        rows.computeIfPresent(deviationKey) { _, d ->
            if (d.workflowRestarts < maxRestarts) d.copy(workflowRestarts = d.workflowRestarts + 1).also { recorded = true } else d
        }
        return recorded
    }

    // Same semantics as the conditional UPDATE: only sets the marker if it is still unset.
    override fun markResolvedEventPublished(deviationKey: String, at: Instant): Boolean {
        var marked = false
        rows.computeIfPresent(deviationKey) { _, d ->
            if (d.resolvedEventPublishedAt == null) d.copy(resolvedEventPublishedAt = at).also { marked = true } else d
        }
        return marked
    }
}

class InMemoryRecoveryAlertRepository : RecoveryAlertRepository {
    val rows = ConcurrentHashMap<String, RecoveryAlert>()
    override fun findByDeviationKey(deviationKey: String) = rows[deviationKey]
    override fun save(alert: RecoveryAlert) = alert.also { rows[it.deviationKey] = it }
}

// Records published DeviationResolved events. failNextPublishes simulates Solace being briefly
// unavailable: that many publish attempts throw before one succeeds.
class RecordingDeviationEventPublisher : DeviationEventPublisherPort {
    val published = java.util.Collections.synchronizedList(mutableListOf<DeviationResolvedEvent>())
    val attempts = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var failNextPublishes = 0
    override fun publishDeviationResolved(event: DeviationResolvedEvent) {
        attempts.incrementAndGet()
        if (failNextPublishes > 0) { failNextPublishes--; throw IllegalStateException("Solace unavailable (simulated)") }
        published += event
    }
}
