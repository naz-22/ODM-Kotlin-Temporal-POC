package com.example.odm.adapters.output.temporal

import com.example.odm.application.ports.output.AutorecoveryActivities
import com.example.odm.application.ports.output.FulfillmentOption
import com.example.odm.application.ports.output.FulfillmentOptionsPort
import com.example.odm.application.ports.output.RebookPort
import com.example.odm.application.ports.output.RebookResult
import com.example.odm.application.service.DeviationRecoveryService
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryResolution
import io.temporal.failure.ApplicationFailure

// Where the workflow's abstract steps become real: DB state via the application
// service, and outbound calls via the Fulfillment Options / Rebook ports.
class AutorecoveryActivitiesImpl(
    private val recoveryService: DeviationRecoveryService,
    private val fulfillmentOptions: FulfillmentOptionsPort,
    private val rebookClient: RebookPort
) : AutorecoveryActivities {

    override fun startAutoRecovery(deviationKey: String) {
        loadAutoRecoverable(deviationKey)
        recoveryService.startAutoRecovery(deviationKey)
    }

    override fun findFulfillmentOption(deviationKey: String): FulfillmentOption? {
        val deviation = loadAutoRecoverable(deviationKey)
        return fulfillmentOptions.findAlternative(
            deviation.orderId, requireNotNull(deviation.itemId), deviation.market, deviation.shortQuantity
        )
    }

    // Note for a real Rebook API: if it accepts the request but the response is lost
    // (timeout), Temporal retries this Activity and the same rebook is sent again. A real
    // integration must pass an idempotency key (deviationKey). The mock does not need one.
    override fun rebookOrder(deviationKey: String, optionId: String): RebookResult {
        val deviation = loadAutoRecoverable(deviationKey)
        return rebookClient.rebook(deviation.orderId, requireNotNull(deviation.itemId), optionId, deviation.shortQuantity)
    }

    override fun requestManualResolution(deviationKey: String, reason: String) =
        recoveryService.requestManualResolution(deviationKey, reason)

    // Resolve (own transaction, committed on return), then announce it on Solace. Both steps are
    // idempotent, so a Temporal retry of this Activity neither re-resolves nor re-publishes.
    override fun closeRecovery(deviationKey: String, resolution: RecoveryResolution, note: String) {
        recoveryService.closeRecovery(deviationKey, resolution, note)
        recoveryService.publishResolvedEventOnce(deviationKey)
    }

    // Last line of defence for the POST_RELEASE invariant, right where the automation's side
    // effects happen - even a workflow started by hand with crafted AUTO_RECOVER input stops
    // here. Non-retryable: retrying can never make a released order auto-recoverable.
    private fun loadAutoRecoverable(deviationKey: String): RecoveryDeviation {
        val deviation = recoveryService.load(deviationKey)
        if (!deviation.releaseStage.allowsAutoRecovery) {
            throw ApplicationFailure.newNonRetryableFailure(
                "$deviationKey is ${deviation.releaseStage}: automated recovery is never allowed",
                "AutoRecoveryNotAllowed"
            )
        }
        return deviation
    }
}
