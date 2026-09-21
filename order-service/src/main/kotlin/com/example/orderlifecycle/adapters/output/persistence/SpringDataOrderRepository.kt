package com.example.orderlifecycle.adapters.output.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataOrderRepository : JpaRepository<OrderEntity, UUID>
