package com.example.orderlifecycle.application.ports.input

import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import java.util.UUID

interface PlaceOrderUseCase {

    fun placeOrder(order: Order): Order

    fun findOrderById(id: UUID): Order?

    fun transitionOrder(id: UUID, newStatus: OrderStatus): Order
}
