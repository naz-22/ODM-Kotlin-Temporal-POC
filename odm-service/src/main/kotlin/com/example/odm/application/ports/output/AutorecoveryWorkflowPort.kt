package com.example.odm.application.ports.output

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.ManualResolution

// State of the latest run of autorecovery-<deviationKey>, as reported by Temporal.
enum class WorkflowRunState {
    NOT_FOUND,
    RUNNING,
    COMPLETED,
    // FAILED, TERMINATED, CANCELED or TIMED_OUT: closed, but not successfully.
    ENDED_WITHOUT_SUCCESS
}

// Keeps the application service free of Temporal client details (and easy to test).
interface AutorecoveryWorkflowPort {

    // Returns false if a workflow for this deviation already exists - never starts a second one.
    fun startIfAbsent(input: AutorecoveryInput): Boolean

    // Starts a new run ONLY if the latest run ended without success. Temporal enforces this
    // atomically, so a RUNNING or COMPLETED run can never be duplicated. Returns false if rejected.
    fun restartAfterFailure(input: AutorecoveryInput): Boolean

    fun currentState(deviationKey: String): WorkflowRunState

    // Returns false if the workflow is not running (it ended FAILED/TERMINATED/CANCELED, or
    // does not exist), so the Signal could not be delivered.
    fun signalManualResolution(deviationKey: String, resolution: ManualResolution): Boolean
}
