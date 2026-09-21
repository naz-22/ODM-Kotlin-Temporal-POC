package com.example.orderlifecycle.application.ports.input

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface // Tells Temporal that this interface defines a Workflow contract.
interface OrderLifecycleWorkflow {

    @WorkflowMethod // Marks this method as the Workflow's entry point.
    fun startOrder(orderId: String): String
    // Kotlin: fun = function; orderId: String = parameter name + type.
    // Java equivalent: String startOrder(String orderId);
    // Returns the final OrderStatus name (COMPLETED, ORDER_FAILED or ORDER_CANCELLED).
}
