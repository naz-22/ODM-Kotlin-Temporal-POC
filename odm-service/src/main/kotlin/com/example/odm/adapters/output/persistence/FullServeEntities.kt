package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.ClosureTrigger
import com.example.odm.domain.model.FullServeStatus
import com.example.odm.domain.model.NotificationKind
import com.example.odm.domain.model.NotificationStatus
import com.example.odm.domain.model.SignalSource
import jakarta.persistence.*
import java.time.Instant

// Read model of fullserve_deviations. Inserts and the OPEN -> CLOSED transition go through the
// conditional native statements in SpringDataFullServeDeviationRepository.
@Entity
@Table(name = "fullserve_deviations")
class FullServeDeviationEntity(

    @Id
    val deviationId: String,

    @Column(nullable = false)
    val orderId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: SignalSource,

    @Column(nullable = false)
    val serviceType: String,

    @Column(nullable = false)
    val market: String,

    @Column(nullable = false)
    val deviationType: String,

    @Column(nullable = false)
    val reasonCode: String,

    @Column(nullable = false)
    val occurrence: String,

    @Column
    val reasonCaused: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: FullServeStatus,

    @Column(nullable = false)
    val workflowId: String,

    @Column(nullable = false)
    val runId: String,

    @Column(length = 500)
    val businessRule: String?,

    @Enumerated(EnumType.STRING)
    @Column
    val closureTrigger: ClosureTrigger?,

    @Enumerated(EnumType.STRING)
    @Column
    val closureSource: SignalSource?,

    @Column(length = 500)
    val closureNote: String?,

    @Column(nullable = false)
    val createdAt: Instant,

    @Column
    val closedAt: Instant?
)

@Entity
@Table(name = "fullserve_notifications")
class FullServeNotificationEntity(

    @Id
    val notificationKey: String,

    @Column(nullable = false)
    val deviationId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: NotificationKind,

    @Column(nullable = false)
    val recipient: String,

    @Column(nullable = false)
    val destination: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: NotificationStatus,

    @Column(length = 500)
    val detail: String?,

    @Column(nullable = false)
    val recordedAt: Instant
)
