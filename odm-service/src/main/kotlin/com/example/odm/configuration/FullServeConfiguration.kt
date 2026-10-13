package com.example.odm.configuration

import com.example.odm.adapters.input.temporal.FullServeDeviationWorkflowImpl
import com.example.odm.adapters.output.temporal.FullServeActivitiesImpl
import com.example.odm.application.ports.input.FullServeDeviationWorkflow
import com.example.odm.domain.model.AlertConsumer
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// odm.fullserve.* - everything about the Full-Serve lifecycle that is configuration rather than
// code: the accepted contract values, the alert schedule and every Solace destination.
@ConfigurationProperties("odm.fullserve")
data class FullServeProperties(
    val contract: Contract = Contract(),
    val alerts: Alerts = Alerts(),
    val destinations: Destinations = Destinations(),
    val solace: Solace = Solace()
) {
    // Values a signal must carry. Defaults are the Full Serve rows of the Phase 1 taxonomy; a new
    // deviation type or reason code (e.g. OEP's Payment deviation type) is a configuration change.
    data class Contract(
        val deviationTypes: Set<String> = setOf("IKEA_DELAYED_ORDER_STO_HANDOUT", "IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT"),
        val reasonCodes: Set<String> = setOf(
            "FAILED_TO_PICKUP", "NO_AVAILABLE_PICKING_CAPACITY", "DELAY_CAUSED_BY_IKEA", "NO_STORE_STOCK", "STORE_SHORT_PICK"
        ),
        val occurrences: Set<String> = setOf("LAST_MILE", "ORDER_PROCESSING", "FULFILMENT")
    )

    // Alert configuration: markets where it exists and is enabled (a Drools rule input), the
    // consumers to alert, and when each alert falls due, measured from deviation creation.
    data class Alerts(
        val enabledMarkets: Set<String> = emptySet(),
        val consumers: List<AlertConsumer> = listOf(AlertConsumer.SPM, AlertConsumer.DYNAMICS),
        val schedule: List<Duration> = listOf(Duration.ZERO)
    ) {
        fun isEnabledFor(market: String) = enabledMarkets.any { it.equals(market, ignoreCase = true) }
    }

    data class Destinations(
        val signalQueue: String = "ODM.DEVIATION.SIGNAL.Q",
        val signalTopic: String = "odm/in/deviation/signal/>",
        val updateQueue: String = "ODM.DEVIATION.UPDATE.Q",
        val updateTopic: String = "odm/in/deviation/update/>",
        val deviationCreated: String = "odm/out/deviation/created",
        // {consumer} and {orderId} are substituted per alert.
        val consumerAlert: String = "odm/out/deviation/alert/{consumer}/{orderId}",
        val alertClosure: String = "odm/out/deviation/closed",
        val deviationClosed: String = "odm/out/deviation/lcm-closed"
    )

    data class Solace(
        // Consume ODM.DEVIATION.SIGNAL.Q / ODM.DEVIATION.UPDATE.Q.
        val listenerEnabled: Boolean = false,
        // Local broker only: create the queues and their topic subscriptions at startup.
        val provisionQueues: Boolean = false,
        // Simulated SPM / Dynamics / O360 subscriber on odm/out/deviation/> (demo only).
        val demoConsumerEnabled: Boolean = false
    )
}

// The Full-Serve worker gets its own WorkerFactory and task queue (odm-fullserve-queue), so the
// autorecovery worker on odm-autorecovery-queue is untouched. Both share ODM's WorkflowClient.
@Configuration
@EnableConfigurationProperties(FullServeProperties::class)
class FullServeConfiguration {

    @Bean(destroyMethod = "shutdown")
    fun fullServeWorkerFactory(workflowClient: WorkflowClient, fullServeActivities: FullServeActivitiesImpl): WorkerFactory {
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(FullServeDeviationWorkflow.TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(FullServeDeviationWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(fullServeActivities)
        factory.start()
        return factory
    }
}
