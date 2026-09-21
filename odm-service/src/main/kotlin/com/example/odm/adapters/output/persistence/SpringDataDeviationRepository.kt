package com.example.odm.adapters.output.persistence

import com.example.odm.domain.model.DeviationType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataDeviationRepository : JpaRepository<DeviationEntity, UUID> {

    fun findByOrderId(orderId: String): List<DeviationEntity>

    fun findByOrderIdAndTypeAndAttemptNumber(orderId: String, type: DeviationType, attemptNumber: Int): DeviationEntity?
}
