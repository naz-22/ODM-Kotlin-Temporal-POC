package com.example.orderlifecycle.application.service

import com.example.orderlifecycle.application.ports.input.OrderLifecycleWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import org.springframework.stereotype.Service

@Service
class OrderWorkflowStarter(
    private val workflowClient: WorkflowClient
) {

    fun start(orderId: String) {

        val workflow = workflowClient.newWorkflowStub(
            OrderLifecycleWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId("order-$orderId")
                .setTaskQueue("order-lifecycle-task-queue")
                .build()
        )
        // Stub = local proxy used to start and communicate with the Temporal Workflow.

        WorkflowClient.start(workflow::startOrder, orderId)
        // Starts the Workflow asynchronously; POST /orders does not wait for the whole lifecycle.
    }
}
