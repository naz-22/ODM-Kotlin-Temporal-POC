package com.example.odm.application.ports.output

import com.example.odm.domain.model.RecoveryResolution
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

data class FulfillmentOption(
    val optionId: String,
    val fulfilmentNode: String
)

// Rebook failure is a normal business answer, returned rather than thrown - throwing
// is reserved for technical failures Temporal should retry (same rule as order-service).
data class RebookResult(
    val succeeded: Boolean,
    val rebookReference: String?,
    val failureReason: String?
)

@ActivityInterface // Every side effect of the Autorecovery Workflow goes through here.
interface AutorecoveryActivities {

    @ActivityMethod
    fun startAutoRecovery(deviationKey: String)
    // Opens the alert (owner AUTORECOVERY) and moves the deviation to AUTO_RECOVERING.

    @ActivityMethod
    fun findFulfillmentOption(deviationKey: String): FulfillmentOption?
    // Calls the (mock) Fulfillment Options API. null = no alternative available.

    @ActivityMethod
    fun rebookOrder(deviationKey: String, optionId: String): RebookResult
    // Calls the (mock) Rebook API with the chosen alternative.

    @ActivityMethod
    fun requestManualResolution(deviationKey: String, reason: String)
    // Opens (or hands over) the alert to RCMP and moves the deviation to AWAITING_MANUAL_RESOLUTION.

    @ActivityMethod
    fun closeRecovery(deviationKey: String, resolution: RecoveryResolution, note: String)
    // Closes the alert and resolves the deviation.
}
