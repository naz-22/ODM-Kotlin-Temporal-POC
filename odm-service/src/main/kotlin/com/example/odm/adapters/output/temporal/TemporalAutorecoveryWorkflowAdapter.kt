package com.example.odm.adapters.output.temporal

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.output.AutorecoveryWorkflowPort
import com.example.odm.application.ports.output.WorkflowRunState
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import org.springframework.stereotype.Component

@Component
class TemporalAutorecoveryWorkflowAdapter(
    private val workflowClient: WorkflowClient
) : AutorecoveryWorkflowPort {

    // Normal start: never allow a second run for the same deviation, even after the first one closed.
    override fun startIfAbsent(input: AutorecoveryInput): Boolean =
        start(input, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)

    // Controlled restart: the server accepts it only if the latest run with this ID ended
    // FAILED/TERMINATED/CANCELED/TIMED_OUT. A RUNNING or COMPLETED run makes the server
    // reject it, and two concurrent restarts -> exactly one succeeds.
    override fun restartAfterFailure(input: AutorecoveryInput): Boolean =
        start(input, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)

    private fun start(input: AutorecoveryInput, reusePolicy: WorkflowIdReusePolicy): Boolean {
        val workflow = workflowClient.newWorkflowStub(
            AutorecoveryWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId(AutorecoveryWorkflow.workflowIdFor(input.deviationKey))
                .setTaskQueue(AutorecoveryWorkflow.TASK_QUEUE)
                .setWorkflowIdReusePolicy(reusePolicy)
                .build()
        )
        return try {
            WorkflowClient.start(workflow::recover, input)
            true
        } catch (e: WorkflowExecutionAlreadyStarted) {
            false // Temporal refused: a run exists that this reuse policy does not allow replacing
        }
    }

    // describe() without a run ID reports the latest run for the workflow ID.
    override fun currentState(deviationKey: String): WorkflowRunState {
        val status = try {
            workflowClient.newUntypedWorkflowStub(AutorecoveryWorkflow.workflowIdFor(deviationKey)).describe().status
        } catch (e: WorkflowNotFoundException) {
            return WorkflowRunState.NOT_FOUND
        }
        return when (status) {
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED -> WorkflowRunState.COMPLETED
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT -> WorkflowRunState.ENDED_WITHOUT_SUCCESS
            // RUNNING, plus anything this workflow never produces (CONTINUED_AS_NEW, UNSPECIFIED):
            // treated as "leave it alone", the conservative choice.
            else -> WorkflowRunState.RUNNING
        }
    }

    override fun signalManualResolution(deviationKey: String, resolution: ManualResolution): Boolean {
        // Stub bound to the already-running workflow by its ID; calling the
        // @SignalMethod sends a real Temporal Signal.
        return try {
            workflowClient
                .newWorkflowStub(AutorecoveryWorkflow::class.java, AutorecoveryWorkflow.workflowIdFor(deviationKey))
                .manualResolution(resolution)
            true
        } catch (e: WorkflowNotFoundException) {
            false // latest run is closed (or missing): Temporal rejects Signals to it
        }
    }
}
