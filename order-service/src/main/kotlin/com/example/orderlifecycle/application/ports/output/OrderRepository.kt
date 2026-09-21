package com.example.orderlifecycle.application.ports.output

import com.example.orderlifecycle.domain.model.Order
import java.util.UUID

interface OrderRepository {

    fun save(order: Order): Order

    fun findById(id: UUID): Order?  //Order? means the result can be null in Kotlin.
}
