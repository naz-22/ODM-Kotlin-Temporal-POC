package com.example.odm.application.ports.input

import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeUpdate
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

enum class FullServeResult {
    CLOSED,
    // An OPEN deviation for the order already existed when this run tried to create one.
    IGNORED_OPEN_EXISTS
}

data class FullServeOutcome(
    val deviationId: String,
    val result: FullServeResult,
    val alertsPublished: Int = 0
)

// One execution per logical Full-Serve deviation: creation, business rules, consumer alerts,
// the durable wait for a resolution update, closure and the closure notifications.
@WorkflowInterface
interface FullServeDeviationWorkflow {

    @WorkflowMethod
    fun handle(signal: FullServeSignal): FullServeOutcome

    // A resolution update for this order's OPEN deviation. Delivered as a Temporal Signal to the
    // already-running execution; an update never starts a workflow.
    @SignalMethod
    fun resolutionUpdate(update: FullServeUpdate)

    companion object {
        const val TASK_QUEUE = "odm-fullserve-queue"

        // At most one OPEN deviation per order, so at most one running execution per order.
        // Temporal refuses a second start while one is running; once it has completed, a later
        // signal for the same order may start a new run (and so a new deviation).
        fun workflowIdFor(orderId: String) = "fullserve-deviation-$orderId"
    }
}
