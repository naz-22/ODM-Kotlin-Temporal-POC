package com.example.odm.adapters.input.temporal

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.output.AutorecoveryActivities
import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryResolution
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

// Orchestration only - deterministic, no DB/HTTP/Drools/Spring here. Every side
// effect is an Activity call; the only "waiting" is Workflow.await on a Signal.
//
//   AUTO_RECOVER: startAutoRecovery -> findFulfillmentOption -> rebookOrder -> closeRecovery
//                 (no option / rebook failed -> fall through to the manual path)
//   MANUAL:       requestManualResolution -> Workflow.await(signal) -> closeRecovery
class AutorecoveryWorkflowImpl : AutorecoveryWorkflow {

    private val activities = Workflow.newActivityStub(
        AutorecoveryActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(15))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .setMaximumAttempts(5) // technical retries only
                    .build()
            )
            .build()
    )

    // Set by the Signal handler below. Workflow state like this is rebuilt from the
    // event history on replay, so it survives worker restarts.
    private var manualResolution: ManualResolution? = null

    // Local-only result of the automated attempt (never serialized).
    private sealed class AutoRecoveryOutcome {
        data object Recovered : AutoRecoveryOutcome()
        data class NeedsManual(val reason: String) : AutoRecoveryOutcome()
    }

    override fun recover(input: AutorecoveryInput): String {
        val key = input.deviationKey

        val manualReason = when (input.decision) {
            RecoveryDecision.AUTO_RECOVER -> {
                activities.startAutoRecovery(key)
                when (val outcome = attemptAutoRecovery(key)) {
                    AutoRecoveryOutcome.Recovered -> return RecoveryResolution.AUTO_RECOVERED.name
                    is AutoRecoveryOutcome.NeedsManual -> "Automated recovery failed: ${outcome.reason}"
                }
            }
            RecoveryDecision.MANUAL -> input.manualReason ?: "Drools decided MANUAL (rule: ${input.decisionRule})"
        }

        activities.requestManualResolution(key, manualReason)

        // Durable wait: no polling, no timer. The workflow is parked here (it can stay
        // for days) until the manualResolution Signal arrives; Temporal then replays
        // the Signal, the condition becomes true and execution continues.
        Workflow.await { manualResolution != null }

        val resolution = manualResolution!!
        activities.closeRecovery(
            key,
            RecoveryResolution.MANUALLY_RESOLVED,
            "Resolved by ${resolution.resolvedBy}: ${resolution.note}"
        )
        return RecoveryResolution.MANUALLY_RESOLVED.name
    }

    private fun attemptAutoRecovery(key: String): AutoRecoveryOutcome {
        val option = activities.findFulfillmentOption(key)
            ?: return AutoRecoveryOutcome.NeedsManual("no alternative fulfillment option available")

        val rebook = activities.rebookOrder(key, option.optionId)
        if (!rebook.succeeded) {
            return AutoRecoveryOutcome.NeedsManual("rebook failed (${rebook.failureReason})")
        }

        activities.closeRecovery(
            key,
            RecoveryResolution.AUTO_RECOVERED,
            "Rebooked via ${option.optionId} at ${option.fulfilmentNode} (ref ${rebook.rebookReference})"
        )
        return AutoRecoveryOutcome.Recovered
    }

    // override fun = implements the interface's @SignalMethod. Only records the
    // payload; the first resolution wins, later duplicates are ignored.
    override fun manualResolution(resolution: ManualResolution) {
        if (manualResolution == null) {
            manualResolution = resolution
        }
    }
}
