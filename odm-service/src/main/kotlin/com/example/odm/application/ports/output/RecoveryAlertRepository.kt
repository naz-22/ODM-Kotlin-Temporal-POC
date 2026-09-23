package com.example.odm.application.ports.output

import com.example.odm.domain.model.RecoveryAlert

interface RecoveryAlertRepository {

    fun findByDeviationKey(deviationKey: String): RecoveryAlert?

    fun save(alert: RecoveryAlert): RecoveryAlert
}
