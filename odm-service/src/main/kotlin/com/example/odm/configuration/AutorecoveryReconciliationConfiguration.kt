package com.example.odm.configuration

import com.example.odm.adapters.input.scheduling.AutorecoveryReconciler
import com.example.odm.application.service.DeviationRecoveryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// Scheduling is enabled only together with the reconciler, and only when explicitly
// switched on - a missing property means disabled (so tests never run it by accident).
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "odm.autorecovery.reconciliation", name = ["enabled"], havingValue = "true")
class AutorecoveryReconciliationConfiguration {

    @Bean
    fun autorecoveryReconciler(recoveryService: DeviationRecoveryService, properties: AutoRecoveryProperties) =
        AutorecoveryReconciler(recoveryService, properties)
}
