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

    override fun createIfAbsent(deviation: Deviation): Deviation {
        val inserted = jpaRepository.insertIfAbsent(
            id = deviation.id,
            orderId = deviation.orderId,
            type = deviation.type.name,
            attemptNumber = deviation.attemptNumber,
            reason = deviation.reason,
            status = deviation.status.name,
            decision = deviation.decision?.name,
            createdAt = deviation.createdAt,
            resolvedAt = deviation.resolvedAt
        )

        if (inserted == 1) return deviation

        // Lost the race: a concurrent writer already committed the row for this key.
        // ON CONFLICT DO NOTHING never threw, so the connection/transaction is still
        // healthy here - safe to just read back whatever they persisted.
        return findByOrderIdAndTypeAndAttemptNumber(deviation.orderId, deviation.type, deviation.attemptNumber)
            ?: error(
                "createIfAbsent reported a conflict but no row exists for " +
                    "orderId=${deviation.orderId}, type=${deviation.type}, attempt=${deviation.attemptNumber}"
            )
    }
}
