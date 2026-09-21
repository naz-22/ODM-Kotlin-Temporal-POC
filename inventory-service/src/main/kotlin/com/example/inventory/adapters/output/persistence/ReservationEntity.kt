package com.example.inventory.adapters.output.persistence

import com.example.inventory.domain.model.ReservationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "inventory_reservations")
class ReservationEntity(

    @Id
    @Column(name = "order_id")
    val orderId: String,

    @Column(nullable = false)
    val productId: String,

    @Column(nullable = false)
    val quantity: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReservationStatus,

    @Column(nullable = false)
    val createdAt: Instant
)
