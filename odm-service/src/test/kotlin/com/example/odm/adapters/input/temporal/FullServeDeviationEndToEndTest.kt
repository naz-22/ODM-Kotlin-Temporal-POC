package com.example.odm.adapters.input.temporal

import com.example.odm.adapters.output.temporal.FullServeActivitiesImpl
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.FullServeDeviationWorkflow
import com.example.odm.application.ports.input.FullServeOutcome
import com.example.odm.application.ports.input.FullServeResult
import com.example.odm.application.ports.input.FullServeSignalMessage
import com.example.odm.application.ports.input.FullServeUpdateMessage
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.application.ports.output.AutorecoveryActivities
import com.example.odm.application.service.FullServeDeviationService
import com.example.odm.application.service.FullServeRulesEngine
import com.example.odm.configuration.FullServeProperties
import com.example.odm.configuration.kotlinAwareTemporalDataConverter
import com.example.odm.domain.model.AlertConsumer
import com.example.odm.domain.model.ClosureTrigger
import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeStatus
import com.example.odm.domain.model.NotificationKind
import com.example.odm.domain.model.NotificationStatus
import com.example.odm.domain.model.RecoveryResolution
import com.example.odm.domain.model.SignalSource
import com.example.odm.support.InMemoryFullServeRepository
import com.example.odm.support.RecordingFullServePublisher
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowNotFoundException
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Full-Serve lifecycle through the real ODM pieces: FullServeDeviationService (ingress) +
// FullServeDeviationWorkflowImpl + FullServeActivitiesImpl + real Drools rules, on Temporal's
// in-memory, time-skipping test server. Only Postgres and Solace are replaced by test doubles.
class FullServeDeviationEndToEndTest {

    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var service: FullServeDeviationService
    private val repository = InMemoryFullServeRepository()
    private val publisher = RecordingFullServePublisher()
    private val autorecoveryActivities: AutorecoveryActivities = mock()

    // Alerts on for US only: first alert immediately, reminders 10 and 30 minutes after creation.
    private val properties = FullServeProperties(
        alerts = FullServeProperties.Alerts(
            enabledMarkets = setOf("US"),
            consumers = listOf(AlertConsumer.SPM, AlertConsumer.DYNAMICS),
            schedule = listOf(Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(30))
        )
    )

    @BeforeEach
    fun setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareTemporalDataConverter).build())
                .build()
        )
        val worker = testEnv.newWorker(FullServeDeviationWorkflow.TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(FullServeDeviationWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(FullServeActivitiesImpl(repository, publisher, FullServeRulesEngine(), properties))

        // The autorecovery worker runs alongside, exactly as in odm-service, to prove Full Serve never reaches it.
        val autorecoveryWorker = testEnv.newWorker(AutorecoveryWorkflow.TASK_QUEUE)
        autorecoveryWorker.registerWorkflowImplementationTypes(AutorecoveryWorkflowImpl::class.java)
        // Forwarding object without @ActivityMethod annotations (see AutorecoveryWorkflowImplTest).
        autorecoveryWorker.registerActivitiesImplementations(object : AutorecoveryActivities {
            override fun startAutoRecovery(deviationKey: String) = autorecoveryActivities.startAutoRecovery(deviationKey)
            override fun findFulfillmentOption(deviationKey: String) = autorecoveryActivities.findFulfillmentOption(deviationKey)
            override fun rebookOrder(deviationKey: String, optionId: String) = autorecoveryActivities.rebookOrder(deviationKey, optionId)
            override fun requestManualResolution(deviationKey: String, reason: String) =
                autorecoveryActivities.requestManualResolution(deviationKey, reason)
            override fun closeRecovery(deviationKey: String, resolution: RecoveryResolution, note: String) =
                autorecoveryActivities.closeRecovery(deviationKey, resolution, note)
        })

        service = FullServeDeviationService(repository, testEnv.workflowClient, properties)
        testEnv.start()
    }

    @AfterEach
    fun tearDown() = testEnv.close()

    private fun signal(orderId: String, market: String = "US") = FullServeSignalMessage(
        orderId = orderId, source = "OE", serviceType = "FULL_SERVE", market = market,
        deviationType = "IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT", reasonCode = "NO_STORE_STOCK",
        occurrence = "ORDER_PROCESSING", reasonCaused = "FULFILLMENT_UNIT_ALLOCATION_FAILURE"
    )

    private fun update(orderId: String, status: String = "RESOLVED", deviationId: String? = null) = FullServeUpdateMessage(
        orderId = orderId, source = "OE", trigger = "COWORKER", resolutionStatus = status,
        deviationId = deviationId, note = "Co-worker rebooked the hand-out"
    )

    private fun workflowId(orderId: String) = FullServeDeviationWorkflow.workflowIdFor(orderId)

    private fun outcomeOf(orderId: String): FullServeOutcome =
        testEnv.workflowClient.newUntypedWorkflowStub(workflowId(orderId)).getResult(FullServeOutcome::class.java)

    private fun workflowStatus(orderId: String): WorkflowExecutionStatus =
        testEnv.workflowClient.newUntypedWorkflowStub(workflowId(orderId)).describe().status

    // Test-side wait for Activities running on worker threads (the workflow itself never polls).
    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    private fun awaitOpenDeviation(orderId: String): FullServeDeviation {
        awaitCondition("OPEN deviation for $orderId") { repository.findOpenByOrderId(orderId) != null }
        return repository.findOpenByOrderId(orderId)!!
    }

    private fun alertsPublished(orderId: String, alertNumber: Int) =
        publisher.to("odm/out/deviation/alert/").filter { it.payload["orderId"] == orderId && it.payload["alertNumber"] == alertNumber }

    @Test
    fun `invalid signal is rejected without creating a deviation or starting a workflow`() {
        val result = service.receiveSignal(signal("ORD-INVALID").copy(reasonCode = "NOT_A_REASON", market = null))

        assertEquals(IntakeOutcome.REJECTED_INVALID, result.outcome)
        assertTrue(result.errors.any { it.startsWith("market is required") })
        assertTrue(result.errors.any { it.startsWith("reasonCode 'NOT_A_REASON'") })
        assertTrue(repository.deviations.isEmpty())
        assertThrows<WorkflowNotFoundException> { workflowStatus("ORD-INVALID") }
    }

    @Test
    fun `Click and Collect or a non-OE-OEP source is not a valid Full-Serve signal`() {
        assertEquals(IntakeOutcome.REJECTED_INVALID, service.receiveSignal(signal("ORD-CC").copy(serviceType = "CLICK_AND_COLLECT")).outcome)
        assertEquals(IntakeOutcome.REJECTED_INVALID, service.receiveSignal(signal("ORD-O360").copy(source = "O360")).outcome)
        assertTrue(repository.deviations.isEmpty())
    }

    @Test
    fun `new deviation is created OPEN, correlated with its workflow, and O360 is notified`() {
        val result = service.receiveSignal(signal("ORD-7741265"))
        assertEquals(IntakeOutcome.ACCEPTED, result.outcome)
        assertEquals("fullserve-deviation-ORD-7741265", result.workflowId)

        val deviation = awaitOpenDeviation("ORD-7741265")
        assertEquals(FullServeStatus.OPEN, deviation.status)
        assertEquals("fullserve-deviation-ORD-7741265", deviation.workflowId)
        assertTrue(deviation.deviationId.startsWith("FSD-"))

        awaitCondition("O360 DeviationCreated") { publisher.to("odm/out/deviation/created").isNotEmpty() }
        val created = publisher.to("odm/out/deviation/created").single()
        assertEquals("DeviationCreated", created.payload["eventType"])
        assertEquals(deviation.deviationId, created.payload["deviationId"])
        assertEquals("NO_STORE_STOCK", created.payload["reasonCode"])
    }

    @Test
    fun `alerts are published to SPM and Dynamics on the configured schedule while the deviation stays OPEN`() {
        service.receiveSignal(signal("ORD-ALERTS"))
        awaitCondition("first alert to both consumers") { alertsPublished("ORD-ALERTS", 1).size == 2 }
        assertEquals(
            setOf("odm/out/deviation/alert/SPM/ORD-ALERTS", "odm/out/deviation/alert/DYNAMICS/ORD-ALERTS"),
            alertsPublished("ORD-ALERTS", 1).map { it.destination }.toSet()
        )

        // The reminder is a durable timer: nothing before 10 minutes, published once it falls due.
        testEnv.sleep(Duration.ofMinutes(9))
        assertTrue(alertsPublished("ORD-ALERTS", 2).isEmpty())
        testEnv.sleep(Duration.ofMinutes(2))
        awaitCondition("second alert") { alertsPublished("ORD-ALERTS", 2).size == 2 }

        // Every alert sent, no update received: the deviation waits, OPEN, for as long as it takes.
        testEnv.sleep(Duration.ofDays(3))
        awaitCondition("third alert") { alertsPublished("ORD-ALERTS", 3).size == 2 }
        assertEquals(FullServeStatus.OPEN, repository.findOpenByOrderId("ORD-ALERTS")?.status)
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, workflowStatus("ORD-ALERTS"))
        assertTrue(publisher.to("odm/out/deviation/closed").isEmpty())
    }

    @Test
    fun `resolving update closes the deviation, skips pending alerts and publishes alert closure and O360 closure`() {
        service.receiveSignal(signal("ORD-CLOSE"))
        val deviation = awaitOpenDeviation("ORD-CLOSE")
        awaitCondition("first alert") { alertsPublished("ORD-CLOSE", 1).size == 2 }

        val signalled = service.receiveUpdate(update("ORD-CLOSE"))
        assertEquals(UpdateOutcome.SIGNALLED, signalled.outcome)
        assertEquals(deviation.deviationId, signalled.deviationId)

        val outcome = outcomeOf("ORD-CLOSE")
        assertEquals(FullServeResult.CLOSED, outcome.result)
        assertEquals(2, outcome.alertsPublished)

        val closed = repository.findById(deviation.deviationId)!!
        assertEquals(FullServeStatus.CLOSED, closed.status)
        assertEquals(ClosureTrigger.COWORKER, closed.closureTrigger)
        assertEquals(SignalSource.OE, closed.closureSource)

        // The reminders at 10 and 30 minutes were still pending - closure cancelled them.
        testEnv.sleep(Duration.ofHours(1))
        assertTrue(alertsPublished("ORD-CLOSE", 2).isEmpty())
        assertTrue(alertsPublished("ORD-CLOSE", 3).isEmpty())

        val alertClosure = publisher.to("odm/out/deviation/closed").single()
        assertEquals("DeviationClosed", alertClosure.payload["eventType"])
        assertEquals(listOf("SPM", "DYNAMICS"), alertClosure.payload["consumers"])
        val lcmClosed = publisher.to("odm/out/deviation/lcm-closed").single()
        assertEquals("DeviationClosedLcm", lcmClosed.payload["eventType"])
        assertEquals("COWORKER", lcmClosed.payload["closureTrigger"])
        assertEquals(2, lcmClosed.payload["alertsSent"])
    }

    @Test
    fun `duplicate signal for an order with an OPEN deviation is ignored`() {
        service.receiveSignal(signal("ORD-DUP"))
        val open = awaitOpenDeviation("ORD-DUP")

        val duplicate = service.receiveSignal(signal("ORD-DUP"))

        assertEquals(IntakeOutcome.IGNORED_DUPLICATE, duplicate.outcome)
        assertEquals(open.deviationId, duplicate.existingDeviationId)
        assertEquals(1, repository.findByOrderId("ORD-DUP").size)
    }

    @Test
    fun `concurrent duplicate signals create exactly one deviation`() {
        val threads = 8
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val results = (1..threads).map { pool.submit<IntakeOutcome> { ready.await(); service.receiveSignal(signal("ORD-RACE")).outcome } }
        ready.countDown()
        val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, outcomes.count { it == IntakeOutcome.ACCEPTED })
        assertTrue(outcomes.all { it in setOf(IntakeOutcome.ACCEPTED, IntakeOutcome.IGNORED_DUPLICATE, IntakeOutcome.RETRY_LATER) })
        awaitOpenDeviation("ORD-RACE")
        assertEquals(1, repository.findByOrderId("ORD-RACE").size)
        // A redelivery of any RETRY_LATER message now resolves to a plain duplicate.
        assertEquals(IntakeOutcome.IGNORED_DUPLICATE, service.receiveSignal(signal("ORD-RACE")).outcome)
    }

    @Test
    fun `unresolved update is delivered but the deviation stays OPEN`() {
        service.receiveSignal(signal("ORD-UNRESOLVED"))
        awaitOpenDeviation("ORD-UNRESOLVED")

        assertEquals(UpdateOutcome.SIGNALLED, service.receiveUpdate(update("ORD-UNRESOLVED", status = "UNRESOLVED")).outcome)

        testEnv.sleep(Duration.ofMinutes(1))
        assertEquals(FullServeStatus.OPEN, repository.findOpenByOrderId("ORD-UNRESOLVED")?.status)
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, workflowStatus("ORD-UNRESOLVED"))
    }

    @Test
    fun `update for an order without an OPEN deviation is ignored and starts no workflow`() {
        val result = service.receiveUpdate(update("ORD-UNKNOWN"))

        assertEquals(UpdateOutcome.NO_OPEN_DEVIATION, result.outcome)
        assertThrows<WorkflowNotFoundException> { workflowStatus("ORD-UNKNOWN") }
        assertTrue(repository.deviations.isEmpty())
    }

    @Test
    fun `invalid update is rejected and never signalled`() {
        service.receiveSignal(signal("ORD-BAD-UPDATE"))
        awaitOpenDeviation("ORD-BAD-UPDATE")

        val result = service.receiveUpdate(update("ORD-BAD-UPDATE").copy(trigger = "AUTO_RECOVERED"))

        assertEquals(UpdateOutcome.REJECTED_INVALID, result.outcome)
        testEnv.sleep(Duration.ofMinutes(1))
        assertEquals(FullServeStatus.OPEN, repository.findOpenByOrderId("ORD-BAD-UPDATE")?.status)
    }

    @Test
    fun `late duplicate resolving update after closure is ignored and publishes nothing more`() {
        service.receiveSignal(signal("ORD-LATE"))
        awaitOpenDeviation("ORD-LATE")
        service.receiveUpdate(update("ORD-LATE"))
        assertEquals(FullServeResult.CLOSED, outcomeOf("ORD-LATE").result)
        val publishedBefore = publisher.published.size

        assertEquals(UpdateOutcome.NO_OPEN_DEVIATION, service.receiveUpdate(update("ORD-LATE")).outcome)
        assertEquals(publishedBefore, publisher.published.size)
    }

    @Test
    fun `update naming a different deviation is treated as stale and does not close the OPEN one`() {
        service.receiveSignal(signal("ORD-STALE"))
        awaitOpenDeviation("ORD-STALE")

        assertEquals(UpdateOutcome.STALE_DEVIATION_ID, service.receiveUpdate(update("ORD-STALE", deviationId = "FSD-earlier")).outcome)
        assertEquals(FullServeStatus.OPEN, repository.findOpenByOrderId("ORD-STALE")?.status)
    }

    @Test
    fun `transient publication failures are retried and each alert is published once`() {
        publisher.failNextPublishes(2) // the O360 DeviationCreated publish fails twice, then succeeds

        service.receiveSignal(signal("ORD-FLAKY"))
        val deviation = awaitOpenDeviation("ORD-FLAKY")
        awaitCondition("first alert") { alertsPublished("ORD-FLAKY", 1).size == 2 }

        assertEquals(1, publisher.to("odm/out/deviation/created").size)
        assertTrue(publisher.attempts.get() >= 5) // 3 attempts for created + 2 alerts
        assertTrue(repository.findNotifications(deviation.deviationId).all { it.status == NotificationStatus.PUBLISHED })
    }

    @Test
    fun `publication that exhausts its retries is recorded FAILED and the lifecycle continues`() {
        publisher.failingDestinations += "/alert/SPM/"

        service.receiveSignal(signal("ORD-SPM-DOWN"))
        val deviation = awaitOpenDeviation("ORD-SPM-DOWN")
        testEnv.sleep(Duration.ofMinutes(1)) // lets Temporal's retry back-off elapse
        awaitCondition("SPM failure recorded") {
            repository.findNotifications(deviation.deviationId).any { it.recipient == "SPM" && it.status == NotificationStatus.FAILED }
        }

        service.receiveUpdate(update("ORD-SPM-DOWN"))
        assertEquals(FullServeResult.CLOSED, outcomeOf("ORD-SPM-DOWN").result)

        // Only Dynamics received an alert, so only Dynamics is told it is closed.
        assertEquals(listOf("DYNAMICS"), publisher.to("odm/out/deviation/closed").single().payload["consumers"])
        assertEquals(1, publisher.to("odm/out/deviation/lcm-closed").size)
    }

    @Test
    fun `market without enabled alert configuration gets no consumer alerts but still closes and notifies O360`() {
        service.receiveSignal(signal("ORD-SE", market = "SE"))
        val deviation = awaitOpenDeviation("ORD-SE")
        testEnv.sleep(Duration.ofHours(1))

        service.receiveUpdate(update("ORD-SE"))
        assertEquals(0, outcomeOf("ORD-SE").alertsPublished)
        assertTrue(publisher.to("odm/out/deviation/alert/").isEmpty())
        assertTrue(publisher.to("odm/out/deviation/closed").isEmpty())
        assertEquals(1, publisher.to("odm/out/deviation/lcm-closed").size)
        assertTrue(repository.findById(deviation.deviationId)!!.businessRule!!.contains("No enabled alert configuration"))
    }

    @Test
    fun `Full Serve never invokes Auto-Recovery`() {
        service.receiveSignal(signal("ORD-NO-AR"))
        awaitOpenDeviation("ORD-NO-AR")
        service.receiveUpdate(update("ORD-NO-AR"))
        assertEquals(FullServeResult.CLOSED, outcomeOf("ORD-NO-AR").result)

        val events = testEnv.workflowClient.fetchHistory(workflowId("ORD-NO-AR")).events
        val activityTypes = events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .map { it.activityTaskScheduledEventAttributes.activityType.name }.toSet()
        assertEquals(
            setOf("CreateDeviation", "NotifyDeviationCreated", "CheckBusinessRules", "PublishConsumerAlert",
                "CloseDeviation", "PublishAlertClosure", "NotifyDeviationClosed"),
            activityTypes
        )
        assertTrue(events.none { it.eventType == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED })
        assertTrue(repository.findByOrderId("ORD-NO-AR").single().businessRule!!.contains("not eligible for auto-recovery"))
        verifyNoInteractions(autorecoveryActivities)
    }

    @Test
    fun `after closure a new signal for the same order opens a new deviation`() {
        service.receiveSignal(signal("ORD-AGAIN"))
        val first = awaitOpenDeviation("ORD-AGAIN")
        service.receiveUpdate(update("ORD-AGAIN"))
        assertEquals(FullServeResult.CLOSED, outcomeOf("ORD-AGAIN").result)

        assertEquals(IntakeOutcome.ACCEPTED, service.receiveSignal(signal("ORD-AGAIN")).outcome)
        val second = awaitOpenDeviation("ORD-AGAIN")
        assertNotEquals(first.deviationId, second.deviationId)
        assertEquals(2, repository.findByOrderId("ORD-AGAIN").size)
    }

    @Test
    fun `Activities are idempotent when Temporal retries them`() {
        val activities = FullServeActivitiesImpl(repository, publisher, FullServeRulesEngine(), properties)
        val signal = com.example.odm.domain.model.FullServeSignal(
            "ORD-RETRY", SignalSource.OE, "FULL_SERVE", "US", "IKEA_DELAYED_ORDER_STO_HANDOUT", "FAILED_TO_PICKUP", "LAST_MILE", null
        )

        assertTrue(activities.createDeviation("FSD-1", "wf", "run", signal))
        assertTrue(activities.createDeviation("FSD-1", "wf", "run", signal)) // retry of the same create
        assertEquals(false, activities.createDeviation("FSD-2", "wf", "run2", signal)) // a different deviation

        activities.publishConsumerAlert("FSD-1", 1, AlertConsumer.SPM)
        activities.publishConsumerAlert("FSD-1", 1, AlertConsumer.SPM) // retry after a successful publish
        assertEquals(1, publisher.to("odm/out/deviation/alert/SPM/").size)

        val resolved = com.example.odm.domain.model.FullServeUpdate(
            "ORD-RETRY", SignalSource.OE, ClosureTrigger.CUSTOMER, com.example.odm.domain.model.ResolutionStatus.RESOLVED, null, null
        )
        activities.closeDeviation("FSD-1", resolved)
        val closedAt = repository.findById("FSD-1")!!.closedAt
        activities.closeDeviation("FSD-1", resolved.copy(trigger = ClosureTrigger.AUTO_CLOSURE))
        assertEquals(ClosureTrigger.CUSTOMER, repository.findById("FSD-1")!!.closureTrigger)
        assertEquals(closedAt, repository.findById("FSD-1")!!.closedAt)

        // A CLOSED deviation never gets another consumer alert.
        activities.publishConsumerAlert("FSD-1", 2, AlertConsumer.SPM)
        assertNull(repository.findNotification("FSD-1:CONSUMER_ALERT:SPM:2"))
        assertEquals(NotificationKind.CONSUMER_ALERT, repository.findNotification("FSD-1:CONSUMER_ALERT:SPM:1")?.kind)
    }
}
