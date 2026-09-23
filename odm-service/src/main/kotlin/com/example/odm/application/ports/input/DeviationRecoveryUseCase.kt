package com.example.odm.application.ports.input

import com.example.odm.domain.model.DeviationSignal
import com.example.odm.domain.model.RecoveryAlert
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryStatus

data class SignalIngestionResult(
    val deviation: RecoveryDeviation,
    // true when this deviationKey had already been ingested - nothing new was started.
    val duplicate: Boolean
)

data class RecoveryRecord(
    val deviation: RecoveryDeviation,
    val alert: RecoveryAlert?
)

// sealed class = a closed hierarchy: every possible outcome is listed here, so a
// `when` over it is checked for exhaustiveness by the compiler.
sealed class ManualResolutionResult {
    data class SignalSent(val workflowId: String) : ManualResolutionResult()
    data object NotFound : ManualResolutionResult()
    data class NotAwaitingManualResolution(val status: RecoveryStatus) : ManualResolutionResult()
    // The row awaits manual resolution but its workflow run is not running, so the Signal was
    // not delivered. Reconciliation restarts the workflow; the caller should retry.
    data class WorkflowNotRunning(val workflowId: String) : ManualResolutionResult()
}

interface DeviationRecoveryUseCase {

    // Upstream deviation signal: ingest, apply config + Drools, start the Autorecovery Workflow.
    fun ingestSignal(signal: DeviationSignal): SignalIngestionResult

    // RCMP/manual resolution: forwarded to the waiting workflow as a Temporal Signal.
    fun submitManualResolution(deviationKey: String, resolution: ManualResolution): ManualResolutionResult

    fun findRecovery(deviationKey: String): RecoveryRecord?
}
