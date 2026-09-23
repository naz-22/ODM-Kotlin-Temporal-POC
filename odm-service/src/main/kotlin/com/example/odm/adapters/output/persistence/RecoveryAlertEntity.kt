package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.AlertOwner
import com.example.odm.domain.model.AlertStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "recovery_alerts")
class RecoveryAlertEntity(

    @Id
    val id: UUID,

    @Column(nullable = false, unique = true)
    val deviationKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AlertStatus,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var owner: AlertOwner,

    @Column(nullable = false, length = 500)
    var reason: String,

    @Column(nullable = false)
    val openedAt: Instant,

    @Column
    var closedAt: Instant?
)
