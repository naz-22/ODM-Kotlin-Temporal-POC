package com.example.orderlifecycle.adapters.output.rest

import com.example.orderlifecycle.application.ports.output.PaymentAttemptResult
import com.example.orderlifecycle.application.ports.output.PaymentClientPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private data class ChargeRequest(
    val orderId: String,
    val customerId: String,
    val amount: BigDecimal,
    val attemptNumber: Int
)

private data class ChargeResponse(val succeeded: Boolean, val failureReason: String?)

@Component
class PaymentRestClient(
    @Value("\${payment.service.base-url}") private val baseUrl: String
) : PaymentClientPort {

    private val restClient = RestClient.create(baseUrl)

    override fun charge(
        orderId: String,
        customerId: String,
        amount: BigDecimal,
        attemptNumber: Int
    ): PaymentAttemptResult {
        val response = restClient.post()
            .uri("/payments")
            .body(ChargeRequest(orderId, customerId, amount, attemptNumber))
            .retrieve()
            .body(ChargeResponse::class.java)
            ?: throw IllegalStateException("Empty response from payment-service")

        return PaymentAttemptResult(response.succeeded, response.failureReason)
    }
}
