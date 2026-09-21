package com.example.orderlifecycle.adapters.output.rest

import com.example.orderlifecycle.application.ports.output.InventoryClientPort
import com.example.orderlifecycle.application.ports.output.InventoryReservationResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

private data class ReserveRequest(val orderId: String, val productId: String, val quantity: Int)
private data class ReserveResponse(val status: String)

@Component
class InventoryRestClient(
    @Value("\${inventory.service.base-url}") private val baseUrl: String
) : InventoryClientPort {

    private val restClient = RestClient.create(baseUrl)

    override fun reserve(orderId: String, productId: String, quantity: Int): InventoryReservationResult {
        val response = restClient.post()
            .uri("/inventory/reservations")
            .body(ReserveRequest(orderId, productId, quantity))
            .retrieve()
            .body(ReserveResponse::class.java)
            ?: throw IllegalStateException("Empty response from inventory-service")

        return InventoryReservationResult.valueOf(response.status)
    }

    override fun release(orderId: String) {
        restClient.post()
            .uri("/inventory/reservations/{orderId}/release", orderId)
            .retrieve()
            .toBodilessEntity()
    }
}
