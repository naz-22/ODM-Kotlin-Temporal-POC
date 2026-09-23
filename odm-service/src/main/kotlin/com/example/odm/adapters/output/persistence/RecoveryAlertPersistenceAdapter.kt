package com.example.odm.adapters.output.persistence

import com.example.odm.application.ports.output.RecoveryAlertRepository
import com.example.odm.domain.model.RecoveryAlert
import org.springframework.stereotype.Component

@Component
class RecoveryAlertPersistenceAdapter(
    private val jpaRepository: SpringDataRecoveryAlertRepository
) : RecoveryAlertRepository {

    override fun findByDeviationKey(deviationKey: String): RecoveryAlert? =
        jpaRepository.findByDeviationKey(deviationKey)?.let {
            RecoveryAlert(it.id, it.deviationKey, it.status, it.owner, it.reason, it.openedAt, it.closedAt)
        }

    override fun save(alert: RecoveryAlert): RecoveryAlert {
        jpaRepository.save(
            RecoveryAlertEntity(
                alert.id, alert.deviationKey, alert.status, alert.owner, alert.reason, alert.openedAt, alert.closedAt
            )
        )
        return alert
    }
}
