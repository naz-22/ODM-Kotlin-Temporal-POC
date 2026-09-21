package com.example.orderlifecycle.adapters.output.rest

import com.example.orderlifecycle.application.ports.output.DeviationDecision
import com.example.orderlifecycle.application.ports.output.DeviationDecisionClientPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

private data class DecisionRequest(
    val orderId: String,
    val type: String,
    val attemptNumber: Int,
    val reason: String
)

private data class DecisionResponse(val decision: String)

@Component
class OdmRestClient(
    @Value("\${odm.service.base-url}") private val baseUrl: String
) : DeviationDecisionClientPort {

    private val restClient = RestClient.create(baseUrl)

    override fun decide(
        orderId: String,
        deviationType: String,
        attemptNumber: Int,
        reason: String
    ): DeviationDecision {
        val response = restClient.post()
            .uri("/deviations/decision")
            .body(DecisionRequest(orderId, deviationType, attemptNumber, reason))
            .retrieve()
            .body(DecisionResponse::class.java)
            ?: throw IllegalStateException("Empty response from odm-service")

        return DeviationDecision.valueOf(response.decision)
    }
}
