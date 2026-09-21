package com.example.odm.adapters.output.persistence

import com.example.odm.application.ports.output.DeviationRepository
import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationType
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DeviationPersistenceAdapter(
    private val jpaRepository: SpringDataDeviationRepository
) : DeviationRepository {

    private fun toDomain(entity: DeviationEntity) = Deviation(
        id = entity.id,
        orderId = entity.orderId,
        type = entity.type,
        attemptNumber = entity.attemptNumber,
        reason = entity.reason,
        status = entity.status,
        decision = entity.decision,
        createdAt = entity.createdAt,
        resolvedAt = entity.resolvedAt
    )

    override fun findById(id: UUID): Deviation? =
        jpaRepository.findById(id).map(::toDomain).orElse(null)

    override fun findByOrderId(orderId: String): List<Deviation> =
        jpaRepository.findByOrderId(orderId).map(::toDomain)

    override fun findByOrderIdAndTypeAndAttemptNumber(orderId: String, type: DeviationType, attemptNumber: Int): Deviation? =
        jpaRepository.findByOrderIdAndTypeAndAttemptNumber(orderId, type, attemptNumber)?.let(::toDomain)

    override fun save(deviation: Deviation): Deviation {
        jpaRepository.save(
            DeviationEntity(
                id = deviation.id,
                orderId = deviation.orderId,
                type = deviation.type,
                attemptNumber = deviation.attemptNumber,
                reason = deviation.reason,
                status = deviation.status,
                decision = deviation.decision,
                createdAt = deviation.createdAt,
                resolvedAt = deviation.resolvedAt
            )
        )
        return deviation
    }
}
