package com.example.odm.application.ports.output

import com.example.odm.domain.model.RecoveryDeviation
import java.time.Instant

interface RecoveryDeviationRepository {

    fun findByKey(deviationKey: String): RecoveryDeviation?

    // Race-safe create keyed by deviation_key (the table's primary key). Returns true
    // if this call inserted the row, false if a row for that key already existed.
    fun insertIfAbsent(deviation: RecoveryDeviation): Boolean

    fun save(deviation: RecoveryDeviation): RecoveryDeviation

    // Unresolved deviations created before `cutoff` - candidates for reconciliation.
    fun findUnresolvedCreatedBefore(cutoff: Instant): List<RecoveryDeviation>

    // Atomically increments workflow_restarts if it is still below `maxRestarts`.
    // Returns false once the limit is reached. A single UPDATE, so it never overwrites
    // other columns and two concurrent reconcilers cannot both slip past the limit.
    fun tryRecordWorkflowRestart(deviationKey: String, maxRestarts: Int): Boolean

    // Records that the DeviationResolved event was published. Conditional (only if not yet
    // set), so it is safe to call again; returns false if it was already recorded.
    fun markResolvedEventPublished(deviationKey: String, at: Instant): Boolean
}
