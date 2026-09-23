package com.example.odm.adapters.input.scheduling

import com.example.odm.application.service.DeviationRecoveryService
import com.example.odm.application.service.ReconciliationOutcome
import com.example.odm.configuration.AutoRecoveryProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant

// ODM application-level safety net - NOT a second orchestrator. On a fixed delay it asks
// DeviationRecoveryService to reconcile unresolved deviations older than the stale threshold
// against what Temporal reports. It never drives recovery steps itself; it only (re)starts
// the one AutorecoveryWorkflow per deviation, and Temporal refuses anything that would
// duplicate a running or completed run. Created only when
// odm.autorecovery.reconciliation.enabled=true (see AutorecoveryReconciliationConfiguration).
class AutorecoveryReconciler(
    private val recoveryService: DeviationRecoveryService,
    private val properties: AutoRecoveryProperties
) {

    private val log = LoggerFactory.getLogger(AutorecoveryReconciler::class.java)

    @Scheduled(
        initialDelayString = "\${odm.autorecovery.reconciliation.interval-ms:30000}",
        fixedDelayString = "\${odm.autorecovery.reconciliation.interval-ms:30000}"
    )
    fun reconcile() {
        val cutoff = Instant.now().minusSeconds(properties.reconciliation.staleAfterSeconds)
        val outcomes = recoveryService.reconcileStale(cutoff)
        // Only log when something other than "still running / already fine" happened.
        outcomes.filterValues { it != ReconciliationOutcome.ALREADY_RUNNING }.takeIf { it.isNotEmpty() }
            ?.let { log.info("Autorecovery reconciliation: {}", it) }
    }
}
