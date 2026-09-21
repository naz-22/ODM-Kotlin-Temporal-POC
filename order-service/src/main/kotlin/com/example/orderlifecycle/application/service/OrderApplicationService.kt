package com.example.orderlifecycle.application.service

import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.application.ports.output.OrderRepository
import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OrderApplicationService(
    private val orderRepository: OrderRepository,
    private val orderWorkflowStarter: OrderWorkflowStarter
) : PlaceOrderUseCase {

    override fun placeOrder(order: Order): Order {
        val savedOrder = orderRepository.save(order)

        orderWorkflowStarter.start(savedOrder.id.toString())
        // Start the Temporal Workflow after the Order is persisted.

        return savedOrder
    }

    override fun findOrderById(id: UUID): Order? =
        orderRepository.findById(id)

    override fun transitionOrder(
        id: UUID,
        newStatus: OrderStatus
    ): Order {
        val order = orderRepository.findById(id)
            ?: throw IllegalArgumentException("Order not found: $id")

        val updatedOrder = order.moveTo(newStatus)

        return orderRepository.save(updatedOrder)
    }
}
