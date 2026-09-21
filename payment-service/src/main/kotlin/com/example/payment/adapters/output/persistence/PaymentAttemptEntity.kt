package com.example.payment.adapters.output.persistence

import com.example.payment.domain.model.PaymentAttemptStatus
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "payment_attempts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["order_id", "attempt_number"])]
)
class PaymentAttemptEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val orderId: String,

    @Column(nullable = false)
    val attemptNumber: Int,

    @Column(nullable = false)
    val customerId: String,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: PaymentAttemptStatus,

    @Column
    val failureReason: String?,

    @Column(nullable = false)
    val createdAt: Instant
)
