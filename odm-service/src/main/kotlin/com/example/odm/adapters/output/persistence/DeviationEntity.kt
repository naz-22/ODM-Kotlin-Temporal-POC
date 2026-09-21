package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.DeviationStatus
import com.example.odm.domain.model.DeviationType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "deviations",
    uniqueConstraints = [UniqueConstraint(columnNames = ["order_id", "type", "attempt_number"])]
)
class DeviationEntity(

    @Id
    val id: UUID,

    @Column(nullable = false)
    val orderId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: DeviationType,

    @Column(nullable = false)
    val attemptNumber: Int,

    @Column(nullable = false, length = 500)
    val reason: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DeviationStatus,

    @Enumerated(EnumType.STRING)
    @Column
    var decision: Decision?,

    @Column(nullable = false)
    val createdAt: Instant,

    @Column
    var resolvedAt: Instant?
)
