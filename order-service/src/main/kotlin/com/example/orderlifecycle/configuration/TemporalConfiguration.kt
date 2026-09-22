package com.example.orderlifecycle.configuration

import com.example.orderlifecycle.adapters.input.temporal.OrderLifecycleWorkflowImpl
import com.example.orderlifecycle.adapters.output.temporal.OrderLifecycleActivitiesImpl
import com.example.orderlifecycle.application.ports.input.OrderLifecycleWorkflow
import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.application.ports.output.DeviationDecisionClientPort
import com.example.orderlifecycle.application.ports.output.InventoryClientPort
import com.example.orderlifecycle.application.ports.output.OrderEventPublisherPort
import com.example.orderlifecycle.application.ports.output.PaymentClientPort
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// Temporal's default JSON payload converter uses a plain classic-Jackson ObjectMapper
// with no Kotlin module, so it cannot construct Kotlin data classes that lack a no-arg
// constructor (e.g. PaymentAttemptResult) when (de)serializing activity/workflow
// payloads. Registering the Kotlin module on Temporal's own default mapper - rather
// than building a bare new one - keeps Temporal's other Jackson configuration intact.
// Shared by production wiring and by OrderLifecycleWorkflowImplTest's test environment.
val kotlinAwareTemporalDataConverter: DataConverter =
    DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule())
    )

@Configuration
class TemporalConfiguration {

    @Bean
    fun workflowServiceStubs(
        @Value("\${temporal.service-address:127.0.0.1:7233}") temporalAddress: String
    ): WorkflowServiceStubs {
        val options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(temporalAddress)
            .build()

        return WorkflowServiceStubs.newServiceStubs(options)
    }

    @Bean
    fun workflowClient(
        workflowServiceStubs: WorkflowServiceStubs
    ): WorkflowClient {
        val options = WorkflowClientOptions.newBuilder()
            .setDataConverter(kotlinAwareTemporalDataConverter)
            .build()

        return WorkflowClient.newInstance(workflowServiceStubs, options)
        // WorkflowClient is the application-facing Temporal client.
    }

    @Bean
    fun orderLifecycleActivities(
        orderUseCase: PlaceOrderUseCase,
        inventoryClient: InventoryClientPort,
        paymentClient: PaymentClientPort,
        deviationDecisionClient: DeviationDecisionClientPort,
        orderEventPublisher: OrderEventPublisherPort
    ): OrderLifecycleActivitiesImpl {
        return OrderLifecycleActivitiesImpl(
            orderUseCase, inventoryClient, paymentClient, deviationDecisionClient, orderEventPublisher
        )
        // Spring creates the Activity and injects the application-layer + output-port dependencies.
    }

    @Bean
    fun workerFactory(
        workflowClient: WorkflowClient,
        orderLifecycleActivities: OrderLifecycleActivitiesImpl,
        @Value("\${temporal.demo.delay-enabled:false}") demoDelayEnabled: Boolean,
        @Value("\${temporal.demo.delay-seconds:10}") demoDelaySeconds: Long
    ): WorkerFactory {

        val factory = WorkerFactory.newInstance(workflowClient)
        // WorkerFactory creates and manages Temporal Workers.

        val worker = factory.newWorker("order-lifecycle-task-queue")
        // Worker listens to this task queue for work assigned by Temporal.

        val demoDelay = if (demoDelayEnabled) Duration.ofSeconds(demoDelaySeconds) else Duration.ZERO
        worker.registerWorkflowImplementationFactory(OrderLifecycleWorkflow::class.java) {
            OrderLifecycleWorkflowImpl(demoDelay)
        }
        // Register the Workflow implementation this Worker can execute. A factory (rather than
        // registerWorkflowImplementationTypes) is used only so the demo delay config can be passed in.

        worker.registerActivitiesImplementations(orderLifecycleActivities)
        // Register the Spring-created Activity instance with the Temporal Worker.

        factory.start()
        // Start the Worker so it can receive work from Temporal.

        return factory
    }
}
