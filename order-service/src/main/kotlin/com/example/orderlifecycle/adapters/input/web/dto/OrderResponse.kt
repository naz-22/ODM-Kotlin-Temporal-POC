package com.example.orderlifecycle.adapters.input.web.dto

import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class OrderResponse(
    val id: UUID,
    val customerId: String,
    val productId: String,
    val quantity: Int,
    val amount: BigDecimal,
    val status: OrderStatus,
    val createdAt: Instant
) {
    companion object {
        fun from(order: Order): OrderResponse {
            return OrderResponse(
                id = order.id,
                customerId = order.customerId,
                productId = order.productId,
                quantity = order.quantity,
                amount = order.amount,
                status = order.status,
                createdAt = order.createdAt
            )
        }
    }
}
