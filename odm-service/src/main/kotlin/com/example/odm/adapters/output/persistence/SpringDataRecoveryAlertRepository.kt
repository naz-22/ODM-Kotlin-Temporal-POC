package com.example.odm.adapters.output.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataRecoveryAlertRepository : JpaRepository<RecoveryAlertEntity, UUID> {

    fun findByDeviationKey(deviationKey: String): RecoveryAlertEntity?
}
