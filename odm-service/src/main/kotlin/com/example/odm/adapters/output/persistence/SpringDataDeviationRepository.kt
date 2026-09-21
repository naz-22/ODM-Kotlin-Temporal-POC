package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.DeviationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SpringDataDeviationRepository : JpaRepository<DeviationEntity, UUID> {

    fun findByOrderId(orderId: String): List<DeviationEntity>

    fun findByOrderIdAndTypeAndAttemptNumber(orderId: String, type: DeviationType, attemptNumber: Int): DeviationEntity?

    // Race-safe insert built directly on the deviations table's own unique constraint
    // (uq_deviations_order_type_attempt, on order_id/type/attempt_number): ON CONFLICT
    // DO NOTHING means a concurrent insert for the same key never throws a
    // DataIntegrityViolationException and never poisons the caller's transaction - it
    // simply reports 0 rows affected, which the caller checks for and reacts to by
    // reading back whichever row actually won.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO deviations (id, order_id, type, attempt_number, reason, status, decision, created_at, resolved_at)
            VALUES (:id, :orderId, :type, :attemptNumber, :reason, :status, :decision, :createdAt, :resolvedAt)
            ON CONFLICT (order_id, type, attempt_number) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("orderId") orderId: String,
        @Param("type") type: String,
        @Param("attemptNumber") attemptNumber: Int,
        @Param("reason") reason: String,
        @Param("status") status: String,
        @Param("decision") decision: String?,
        @Param("createdAt") createdAt: Instant,
        @Param("resolvedAt") resolvedAt: Instant?
    ): Int
}
