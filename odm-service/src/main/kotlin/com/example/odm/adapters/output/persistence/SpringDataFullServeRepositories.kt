package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.FullServeStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface SpringDataFullServeDeviationRepository : JpaRepository<FullServeDeviationEntity, String> {

    fun findByOrderIdAndStatus(orderId: String, status: FullServeStatus): FullServeDeviationEntity?

    fun findByOrderIdOrderByCreatedAt(orderId: String): List<FullServeDeviationEntity>

    // Race-safe create (same pattern as SpringDataDeviationRepository.insertIfAbsent). ON CONFLICT
    // without a target covers both the primary key (a retried create) and the partial unique index
    // on OPEN deviations per order (a duplicate): either way 0 rows, never an exception.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO fullserve_deviations (deviation_id, order_id, source, service_type, market, deviation_type,
                reason_code, occurrence, reason_caused, status, workflow_id, run_id, created_at)
            VALUES (:deviationId, :orderId, :source, :serviceType, :market, :deviationType,
                :reasonCode, :occurrence, :reasonCaused, 'OPEN', :workflowId, :runId, :createdAt)
            ON CONFLICT DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("deviationId") deviationId: String,
        @Param("orderId") orderId: String,
        @Param("source") source: String,
        @Param("serviceType") serviceType: String,
        @Param("market") market: String,
        @Param("deviationType") deviationType: String,
        @Param("reasonCode") reasonCode: String,
        @Param("occurrence") occurrence: String,
        @Param("reasonCaused") reasonCaused: String?,
        @Param("workflowId") workflowId: String,
        @Param("runId") runId: String,
        @Param("createdAt") createdAt: Instant
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE fullserve_deviations SET business_rule = :businessRule WHERE deviation_id = :deviationId", nativeQuery = true)
    fun updateBusinessRule(@Param("deviationId") deviationId: String, @Param("businessRule") businessRule: String): Int

    // Conditional transition: 1 row = closed now, 0 = already CLOSED (a retry or duplicate).
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            UPDATE fullserve_deviations
            SET status = 'CLOSED', closure_trigger = :trigger, closure_source = :source, closure_note = :note, closed_at = :closedAt
            WHERE deviation_id = :deviationId AND status = 'OPEN'
        """,
        nativeQuery = true
    )
    fun closeIfOpen(
        @Param("deviationId") deviationId: String,
        @Param("trigger") trigger: String,
        @Param("source") source: String,
        @Param("note") note: String?,
        @Param("closedAt") closedAt: Instant
    ): Int
}

interface SpringDataFullServeNotificationRepository : JpaRepository<FullServeNotificationEntity, String> {

    fun findByDeviationIdOrderByRecordedAt(deviationId: String): List<FullServeNotificationEntity>
}
