package com.example.payment.adapters.output.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataPaymentAttemptRepository : JpaRepository<PaymentAttemptEntity, UUID> {

    fun findByOrderIdAndAttemptNumber(orderId: String, attemptNumber: Int): PaymentAttemptEntity?
}
