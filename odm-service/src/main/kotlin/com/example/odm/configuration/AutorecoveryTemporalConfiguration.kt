package com.example.odm.configuration

import com.example.odm.adapters.input.temporal.AutorecoveryWorkflowImpl
import com.example.odm.adapters.output.temporal.AutorecoveryActivitiesImpl
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.output.FulfillmentOptionsPort
import com.example.odm.application.ports.output.RebookPort
import com.example.odm.application.service.DeviationRecoveryService
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
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// Same approach as order-service's TemporalConfiguration: register the Kotlin module on
// Temporal's own default Jackson 2 mapper so Kotlin data classes (AutorecoveryInput,
// ManualResolution, FulfillmentOption, RebookResult) round-trip through payloads.
val kotlinAwareTemporalDataConverter: DataConverter =
    DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
        JacksonJsonPayloadConverter(JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule())
    )

// ODM's own Temporal worker, used only by the autorecovery capability. It polls
// odm-autorecovery-queue - never order-service's order-lifecycle-task-queue.
@Configuration
@EnableConfigurationProperties(AutoRecoveryProperties::class)
class AutorecoveryTemporalConfiguration {

    @Bean
    fun workflowServiceStubs(
        @Value("\${temporal.service-address:127.0.0.1:7233}") temporalAddress: String
    ): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(temporalAddress).build()
        )

    @Bean
    fun workflowClient(workflowServiceStubs: WorkflowServiceStubs): WorkflowClient =
        WorkflowClient.newInstance(
            workflowServiceStubs,
            WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareTemporalDataConverter).build()
        )

    @Bean
    fun autorecoveryActivities(
        recoveryService: DeviationRecoveryService,
        fulfillmentOptions: FulfillmentOptionsPort,
        rebookClient: RebookPort
    ) = AutorecoveryActivitiesImpl(recoveryService, fulfillmentOptions, rebookClient)

    @Bean(destroyMethod = "shutdown")
    fun autorecoveryWorkerFactory(
        workflowClient: WorkflowClient,
        autorecoveryActivities: AutorecoveryActivitiesImpl
    ): WorkerFactory {
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(AutorecoveryWorkflow.TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(AutorecoveryWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(autorecoveryActivities)
        factory.start()
        return factory
    }
}
