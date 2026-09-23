package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.RecoveryResolution
import com.example.odm.domain.model.RecoveryStatus
import com.example.odm.domain.model.ReleaseStage
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "recovery_deviations")
class RecoveryDeviationEntity(

    @Id
    val deviationKey: String,

    @Column(nullable = false)
    val orderId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: RecoveryDeviationType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val releaseStage: ReleaseStage,

    @Column(nullable = false)
    val market: String,

    @Column
    val itemId: String?,

    @Column
    val requestedQuantity: Int?,

    @Column
    val pickedQuantity: Int?,

    @Column(nullable = false, length = 500)
    val reason: String,

    @Column(nullable = false)
    val autoRecoveryEnabled: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val decision: RecoveryDecision,

    @Column(nullable = false)
    val decisionRule: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: RecoveryStatus,

    @Enumerated(EnumType.STRING)
    @Column
    var resolution: RecoveryResolution?,

    @Column(length = 500)
    var resolutionNote: String?,

    @Column(nullable = false)
    val workflowId: String,

    @Column(nullable = false)
    val createdAt: Instant,

    @Column
    var resolvedAt: Instant?,

    @Column(nullable = false)
    var workflowRestarts: Int
)
