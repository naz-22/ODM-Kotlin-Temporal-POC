package com.example.payment.application.service

import com.example.payment.application.ports.input.PaymentOutcome
import com.example.payment.application.ports.input.ProcessPaymentUseCase
import com.example.payment.application.ports.output.PaymentAttemptRepository
import com.example.payment.application.ports.output.PaymentEventPublisherPort
import com.example.payment.domain.model.PaymentAttempt
import com.example.payment.domain.model.PaymentAttemptStatus
import com.example.payment.domain.model.PaymentDecisionRules
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Service
class PaymentApplicationService(
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val eventPublisher: PaymentEventPublisherPort
) : ProcessPaymentUseCase {

    private val log = LoggerFactory.getLogger(PaymentApplicationService::class.java)

    @Transactional
    override fun charge(orderId: String, customerId: String, amount: BigDecimal, attemptNumber: Int): PaymentOutcome {
        paymentAttemptRepository.findByOrderIdAndAttemptNumber(orderId, attemptNumber)?.let { existing ->
            log.info("Payment attempt already recorded: orderId={}, attempt={} - returning idempotent result",
                orderId, attemptNumber)
            return PaymentOutcome(existing.status == PaymentAttemptStatus.SUCCEEDED, existing.failureReason)
        }

        val (status, failureReason) = PaymentDecisionRules.decide(customerId, attemptNumber)

        paymentAttemptRepository.save(
            PaymentAttempt(orderId, attemptNumber, customerId, amount, status, failureReason, Instant.now())
        )

        if (status == PaymentAttemptStatus.SUCCEEDED) {
            eventPublisher.publishPaymentSucceeded(orderId, amount)
            log.info("Payment succeeded: orderId={}, attempt={}", orderId, attemptNumber)
        } else {
            eventPublisher.publishPaymentFailed(orderId, attemptNumber, failureReason ?: "unknown")
            log.info("Payment failed: orderId={}, attempt={}, reason={}", orderId, attemptNumber, failureReason)
        }

        return PaymentOutcome(status == PaymentAttemptStatus.SUCCEEDED, failureReason)
    }
}
