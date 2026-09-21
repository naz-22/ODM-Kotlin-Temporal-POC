package com.example.payment.adapters.output.persistence

import com.example.payment.application.ports.output.PaymentAttemptRepository
import com.example.payment.domain.model.PaymentAttempt
import org.springframework.stereotype.Component

@Component
class PaymentPersistenceAdapter(
    private val jpaRepository: SpringDataPaymentAttemptRepository
) : PaymentAttemptRepository {

    override fun findByOrderIdAndAttemptNumber(orderId: String, attemptNumber: Int): PaymentAttempt? =
        jpaRepository.findByOrderIdAndAttemptNumber(orderId, attemptNumber)?.let {
            PaymentAttempt(it.orderId, it.attemptNumber, it.customerId, it.amount, it.status, it.failureReason, it.createdAt)
        }

    override fun save(attempt: PaymentAttempt): PaymentAttempt {
        jpaRepository.save(
            PaymentAttemptEntity(
                orderId = attempt.orderId,
                attemptNumber = attempt.attemptNumber,
                customerId = attempt.customerId,
                amount = attempt.amount,
                status = attempt.status,
                failureReason = attempt.failureReason,
                createdAt = attempt.createdAt
            )
        )
        return attempt
    }
}
