package com.example.odm.configuration

import com.example.odm.domain.model.RecoveryDeviationType
import org.springframework.boot.context.properties.ConfigurationProperties

// ODM-owned auto-recovery policy: (deviationType + market) -> enabled. Bound from
// application.properties, e.g.  odm.autorecovery.enabled-markets[SHORT_PICK]=US,IN
// Anything not listed is disabled. Drools receives the resolved boolean as a Fact field.
@ConfigurationProperties("odm.autorecovery")
data class AutoRecoveryProperties(
    val enabledMarkets: Map<RecoveryDeviationType, Set<String>> = emptyMap(),
    val reconciliation: Reconciliation = Reconciliation()
) {
    fun isAutoRecoveryEnabled(type: RecoveryDeviationType, market: String): Boolean =
        enabledMarkets[type].orEmpty().any { it.equals(market, ignoreCase = true) }

    // odm.autorecovery.reconciliation.* - the background reconciler (off unless enabled).
    data class Reconciliation(
        val enabled: Boolean = false,
        val intervalMs: Long = 30_000,
        // Only rows older than this are reconciled, so a signal whose workflow start is
        // still in flight is left alone.
        val staleAfterSeconds: Long = 30,
        // Upper bound on restarts of runs that ended without success, per deviation.
        val maxWorkflowRestarts: Int = 3
    )
}
