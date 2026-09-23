package com.example.odm.application.ports.input

import com.example.odm.domain.model.RecoveryDecision
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

// Workflow input: the decision ODM/Drools already made before the workflow started.
data class AutorecoveryInput(
    val deviationKey: String,
    val decision: RecoveryDecision,
    val decisionRule: String,
    // Set only when ODM restarts a run that ended without success and hands the case to
    // RCMP instead of re-running automation. Defaults to null so older payloads still parse.
    val manualReason: String? = null
)

// Payload of the RCMP manual-resolution Temporal Signal.
data class ManualResolution(
    val resolvedBy: String,
    val note: String
)

@WorkflowInterface // Tells Temporal this interface is a Workflow contract.
interface AutorecoveryWorkflow {

    @WorkflowMethod // The Workflow's entry point; returns the final RecoveryResolution name.
    fun recover(input: AutorecoveryInput): String

    // @SignalMethod = an asynchronous message sent INTO a running workflow. Temporal
    // records it durably in the workflow history; the handler only stores the payload,
    // and Workflow.await(...) in the implementation wakes up because of it.
    @SignalMethod
    fun manualResolution(resolution: ManualResolution)

    // A companion object on an interface: shared constants/helpers, like Java static members.
    companion object {
        const val TASK_QUEUE = "odm-autorecovery-queue"

        // Deterministic workflow ID = the idempotency key for workflow start: Temporal
        // refuses a second execution with the same ID (see TemporalAutorecoveryWorkflowAdapter).
        fun workflowIdFor(deviationKey: String) = "autorecovery-$deviationKey"
    }
}
