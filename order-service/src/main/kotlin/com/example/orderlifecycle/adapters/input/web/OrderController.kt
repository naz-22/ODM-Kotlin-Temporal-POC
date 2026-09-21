package com.example.orderlifecycle.adapters.input.web

import com.example.orderlifecycle.adapters.input.web.dto.OrderResponse
import com.example.orderlifecycle.adapters.input.web.dto.PlaceOrderRequest
import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/orders")
class OrderController(
    private val placeOrderUseCase: PlaceOrderUseCase
    // Constructor injection: Spring provides the implementation of this input port.
) {

    @PostMapping
    fun placeOrder(@Valid @RequestBody request: PlaceOrderRequest): OrderResponse {
        // @RequestBody converts incoming JSON into a PlaceOrderRequest object.

        val order = Order(
            id = UUID.randomUUID(),
            customerId = request.customerId,
            productId = request.productId,
            quantity = request.quantity,
            amount = request.amount,
            status = OrderStatus.ORDER_PLACED,
            createdAt = Instant.now()
        )

        return OrderResponse.from(placeOrderUseCase.placeOrder(order))
    }

    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: UUID): ResponseEntity<OrderResponse> {
        val order = placeOrderUseCase.findOrderById(id)
            ?: return ResponseEntity.notFound().build() // ?: returns 404 when no order exists

        return ResponseEntity.ok(OrderResponse.from(order))
    }

    // Intentionally NO "PUT /orders/{id}/status" endpoint here. Order status is a
    // consequence of the Temporal workflow driving business outcomes, not something
    // an API caller may set directly - see docs/architecture.md.
}
