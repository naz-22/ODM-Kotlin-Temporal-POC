package com.example.inventory.adapters.input.web

import com.example.inventory.adapters.input.web.dto.ReservationResponse
import com.example.inventory.adapters.input.web.dto.ReserveInventoryRequest
import com.example.inventory.application.ports.input.ReserveInventoryUseCase
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/inventory")
class InventoryController(
    private val reserveInventoryUseCase: ReserveInventoryUseCase
) {

    // Backs the Temporal "reserveInventory" Activity. Always returns 200 with the
    // outcome in the body - an out-of-stock product is a normal business result,
    // not an error the caller (Temporal) should treat as a technical failure.
    @PostMapping("/reservations")
    fun reserve(@Valid @RequestBody request: ReserveInventoryRequest): ReservationResponse {
        val outcome = reserveInventoryUseCase.reserve(request.orderId, request.productId, request.quantity)
        return ReservationResponse(outcome.name)
    }

    // Idempotent: releasing an order with no reservation, or one already released,
    // is a no-op rather than an error - this backs the compensation Activity, which
    // Temporal may legitimately call more than once.
    @PostMapping("/reservations/{orderId}/release")
    fun release(@PathVariable orderId: String) {
        reserveInventoryUseCase.release(orderId)
    }
}
