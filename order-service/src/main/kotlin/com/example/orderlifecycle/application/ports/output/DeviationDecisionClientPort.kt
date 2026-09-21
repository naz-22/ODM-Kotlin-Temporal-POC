package com.example.orderlifecycle.application.ports.output

// Output port toward odm-service's synchronous decision API. This is the single
// integration point where business retry/compensation/failure decisions are made.
interface DeviationDecisionClientPort {

    fun decide(
        orderId: String,
        deviationType: String,
        attemptNumber: Int,
        reason: String
    ): DeviationDecision
}
