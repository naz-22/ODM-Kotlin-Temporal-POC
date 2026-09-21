package com.example.orderlifecycle.adapters.output.persistence

import com.example.orderlifecycle.domain.model.OrderStatus
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity // JPA entity: this class represents a database table.
@Table(name = "orders")
class OrderEntity(

    @Id // Primary key of the table.
    val id: UUID,

    @Column(nullable = false)
    val customerId: String,

    @Column(nullable = false)
    val productId: String,

    @Column(nullable = false)
    val quantity: Int,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING) // Store enum as text, e.g. ORDER_PLACED.
    @Column(nullable = false)
    var status: OrderStatus,

    @Column(nullable = false)
    val createdAt: Instant
)
