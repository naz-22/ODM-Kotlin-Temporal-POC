package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.RecoveryStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface SpringDataRecoveryDeviationRepository : JpaRepository<RecoveryDeviationEntity, String> {

    fun findByStatusNotAndCreatedAtBefore(status: RecoveryStatus, cutoff: Instant): List<RecoveryDeviationEntity>

    // Conditional atomic increment: 1 row updated = restart allowed, 0 = limit reached.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            UPDATE recovery_deviations SET workflow_restarts = workflow_restarts + 1
            WHERE deviation_key = :deviationKey AND workflow_restarts < :maxRestarts
        """,
        nativeQuery = true
    )
    fun incrementWorkflowRestartsBelow(
        @Param("deviationKey") deviationKey: String,
        @Param("maxRestarts") maxRestarts: Int
    ): Int

    // Same race-safe pattern as SpringDataDeviationRepository.insertIfAbsent: a duplicate
    // signal for the same deviation_key (primary key) reports 0 rows instead of throwing.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO recovery_deviations (deviation_key, order_id, type, release_stage, market, item_id,
                requested_quantity, picked_quantity, reason, auto_recovery_enabled, decision, decision_rule,
                status, resolution, resolution_note, workflow_id, created_at, resolved_at)
            VALUES (:deviationKey, :orderId, :type, :releaseStage, :market, :itemId,
                :requestedQuantity, :pickedQuantity, :reason, :autoRecoveryEnabled, :decision, :decisionRule,
                :status, NULL, NULL, :workflowId, :createdAt, NULL)
            ON CONFLICT (deviation_key) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("deviationKey") deviationKey: String,
        @Param("orderId") orderId: String,
        @Param("type") type: String,
        @Param("releaseStage") releaseStage: String,
        @Param("market") market: String,
        @Param("itemId") itemId: String?,
        @Param("requestedQuantity") requestedQuantity: Int?,
        @Param("pickedQuantity") pickedQuantity: Int?,
        @Param("reason") reason: String,
        @Param("autoRecoveryEnabled") autoRecoveryEnabled: Boolean,
        @Param("decision") decision: String,
        @Param("decisionRule") decisionRule: String,
        @Param("status") status: String,
        @Param("workflowId") workflowId: String,
        @Param("createdAt") createdAt: Instant
    ): Int
}
