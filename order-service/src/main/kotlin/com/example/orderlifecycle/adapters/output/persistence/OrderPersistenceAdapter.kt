package com.example.orderlifecycle.adapters.output.persistence

import com.example.orderlifecycle.application.ports.output.OrderRepository
import com.example.orderlifecycle.domain.model.Order
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OrderPersistenceAdapter(
    private val orderJpaRepository: SpringDataOrderRepository
) : OrderRepository {

    override fun save(order: Order): Order {
        val entity = OrderEntity(
            id = order.id,
            customerId = order.customerId,
            productId = order.productId,
            quantity = order.quantity,
            amount = order.amount,
            status = order.status,
            createdAt = order.createdAt
        )

        orderJpaRepository.save(entity)

        return order
    }

    override fun findById(id: UUID): Order? {
        return orderJpaRepository.findById(id)
            .map { entity ->
                Order(
                    id = entity.id,
                    customerId = entity.customerId,
                    productId = entity.productId,
                    quantity = entity.quantity,
                    amount = entity.amount,
                    status = entity.status,
                    createdAt = entity.createdAt
                )
            }
            .orElse(null)
    }
}
