package com.example.odm.adapters.input.temporal

import java.time.Instant
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doThrow
import org.junit.jupiter.api.assertThrows
import io.temporal.client.WorkflowFailedException
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.application.service.ReconciliationOutcome
import com.example.odm.application.ports.output.WorkflowRunState
import com.example.odm.adapters.input.scheduling.AutorecoveryReconciler
import com.example.odm.adapters.output.fulfillment.MockFulfillmentOptionsClient
import com.example.odm.adapters.output.fulfillment.MockRebookClient
import com.example.odm.adapters.output.temporal.AutorecoveryActivitiesImpl
import com.example.odm.adapters.output.temporal.TemporalAutorecoveryWorkflowAdapter
import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.input.ManualResolutionResult
import com.example.odm.application.service.AutorecoveryDecisionEngine
import com.example.odm.application.service.DeviationRecoveryService
import com.example.odm.configuration.AutoRecoveryProperties
import com.example.odm.configuration.kotlinAwareTemporalDataConverter
import com.example.odm.domain.model.AlertOwner
import com.example.odm.domain.model.AlertStatus
import com.example.odm.domain.model.DeviationSignal
import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.RecoveryResolution
import com.example.odm.domain.model.RecoveryStatus
import com.example.odm.domain.model.ReleaseStage
import com.example.odm.support.InMemoryRecoveryAlertRepository
import com.example.odm.support.InMemoryRecoveryDeviationRepository
import io.temporal.client.WorkflowClientOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Scenario 5 and 6 through the real ODM pieces: DeviationRecoveryService + real Drools
// + TemporalAutorecoveryWorkflowAdapter + AutorecoveryWorkflowImpl + AutorecoveryActivitiesImpl
// + the deterministic mock clients, on Temporal's in-memory test server. Only Postgres
// is replaced (in-memory repositories); the mock clients are spied to verify calls.
class AutorecoveryEndToEndTest {

    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var service: DeviationRecoveryService
    private lateinit var workflowAdapter: TemporalAutorecoveryWorkflowAdapter
    private val deviations = InMemoryRecoveryDeviationRepository()
    private val alerts = InMemoryRecoveryAlertRepository()
    private val fulfillmentOptions = spy(MockFulfillmentOptionsClient())
    private val rebook = spy(MockRebookClient())
    private val properties = AutoRecoveryProperties(
        mapOf(RecoveryDeviationType.SHORT_PICK to setOf("US", "IN")),
        AutoRecoveryProperties.Reconciliation(staleAfterSeconds = 30, maxWorkflowRestarts = 3)
    )

    @BeforeEach
    fun setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareTemporalDataConverter).build()
                )
                .build()
        )
        workflowAdapter = TemporalAutorecoveryWorkflowAdapter(testEnv.workflowClient)
        service = DeviationRecoveryService(
            deviations, alerts, AutorecoveryDecisionEngine(),
            properties,
            workflowAdapter
        )
        val worker = testEnv.newWorker(AutorecoveryWorkflow.TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(AutorecoveryWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(AutorecoveryActivitiesImpl(service, fulfillmentOptions, rebook))
        testEnv.start()
    }

    @AfterEach
    fun tearDown() = testEnv.close()

    private fun shortPick(key: String, itemId: String) = DeviationSignal(
        key, "order-$key", RecoveryDeviationType.SHORT_PICK, ReleaseStage.PRE_RELEASE, "US",
        itemId, requestedQuantity = 5, pickedQuantity = 3, reason = "Only 3 of 5 picked"
    )

    private fun providedServiceFailure(key: String) = DeviationSignal(
        key, "order-$key", RecoveryDeviationType.PROVIDED_SERVICE_FAILURE, ReleaseStage.POST_RELEASE, "US",
        null, null, null, "Last-mile carrier could not deliver"
    )

    private fun resultOf(key: String): String =
        testEnv.workflowClient.newUntypedWorkflowStub(AutorecoveryWorkflow.workflowIdFor(key)).getResult(String::class.java)

    // Test-side wait for the Activity to have recorded AWAITING (the workflow itself never polls).
    private fun awaitStatus(key: String, status: RecoveryStatus) {
        val deadline = System.currentTimeMillis() + 5_000
        while (deviations.rows[key]?.status != status) {
            check(System.currentTimeMillis() < deadline) { "$key never reached $status" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `Demo A - pre-release short pick auto recovers via fulfillment option and rebook, alert CLOSED`() {
        val ingested = service.ingestSignal(shortPick("sp-a", "ITEM-1"))
        assertEquals(RecoveryDecision.AUTO_RECOVER, ingested.deviation.decision)

        assertEquals("AUTO_RECOVERED", resultOf("sp-a"))

        verify(fulfillmentOptions).findAlternative("order-sp-a", "ITEM-1", "US", 2)
        verify(rebook).rebook("order-sp-a", "ITEM-1", "FO-ITEM-1-ALT", 2)
        val deviation = deviations.rows.getValue("sp-a")
        assertEquals(RecoveryStatus.RESOLVED, deviation.status)
        assertEquals(RecoveryResolution.AUTO_RECOVERED, deviation.resolution)
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("sp-a").status)
        assertEquals(AlertOwner.AUTORECOVERY, alerts.rows.getValue("sp-a").owner)
    }

    @Test
    fun `short pick with no alternative escalates to RCMP and is closed by the manual signal`() {
        service.ingestSignal(shortPick("sp-b", MockFulfillmentOptionsClient.NO_ALTERNATIVE_ITEM))
        awaitStatus("sp-b", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)

        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("sp-b").owner)
        verify(rebook, never()).rebook(any(), any(), any(), any())

        service.submitManualResolution("sp-b", ManualResolution("rcmp-agent", "Customer accepted partial"))
        assertEquals("MANUALLY_RESOLVED", resultOf("sp-b"))
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("sp-b").status)
    }

    @Test
    fun `short pick with rebook failure escalates to RCMP`() {
        service.ingestSignal(shortPick("sp-c", MockRebookClient.REBOOK_FAILURE_ITEM))
        awaitStatus("sp-c", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)

        verify(rebook).rebook(any(), any(), any(), any())
        assertEquals(AlertStatus.OPEN, alerts.rows.getValue("sp-c").status)
        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("sp-c").owner)
    }

    @Test
    fun `Demo B - post-release provided service failure waits for RCMP, signal resumes it, alert CLOSED`() {
        val ingested = service.ingestSignal(providedServiceFailure("psf-b"))
        assertEquals(RecoveryDecision.MANUAL, ingested.deviation.decision)
        awaitStatus("psf-b", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        assertEquals(AlertStatus.OPEN, alerts.rows.getValue("psf-b").status)
        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("psf-b").owner)

        val result = service.submitManualResolution("psf-b", ManualResolution("rcmp-agent", "Redelivery booked"))
        assertIs<ManualResolutionResult.SignalSent>(result)

        assertEquals("MANUALLY_RESOLVED", resultOf("psf-b"))
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("psf-b").status)
        assertEquals("Resolved by rcmp-agent: Redelivery booked", deviations.rows.getValue("psf-b").resolutionNote)
        verify(fulfillmentOptions, never()).findAlternative(any(), any(), any(), any())
        verify(rebook, never()).rebook(any(), any(), any(), any())
    }

    @Test
    fun `duplicate signals start exactly one workflow and recover exactly once`() {
        val first = service.ingestSignal(shortPick("sp-dup", "ITEM-1"))
        val second = service.ingestSignal(shortPick("sp-dup", "ITEM-1"))
        assertFalse(first.duplicate)
        assertTrue(second.duplicate)

        assertEquals("AUTO_RECOVERED", resultOf("sp-dup"))
        service.ingestSignal(shortPick("sp-dup", "ITEM-1"))

        verify(fulfillmentOptions, times(1)).findAlternative(any(), any(), any(), any())
        verify(rebook, times(1)).rebook(any(), any(), any(), any())
    }

    @Test
    fun `Temporal rejects a second workflow start for the same deviation, even after the first completed`() {
        service.ingestSignal(shortPick("sp-id", "ITEM-1"))
        assertEquals("AUTO_RECOVERED", resultOf("sp-id"))

        val again = workflowAdapter.startIfAbsent(AutorecoveryInput("sp-id", RecoveryDecision.AUTO_RECOVER, "r"))

        assertFalse(again)
        verify(rebook, times(1)).rebook(any(), any(), any(), any())
    }

    // --- Reliability: reconciliation and controlled restart --------------------------

    private fun stub(key: String) = testEnv.workflowClient.newUntypedWorkflowStub(AutorecoveryWorkflow.workflowIdFor(key))

    // Simulates "DB insert committed, then ODM crashed before starting the workflow".
    private fun orphanedRow(signal: DeviationSignal, decision: RecoveryDecision) {
        deviations.insertIfAbsent(
            RecoveryDeviation.received(signal, true, decision, "rule", AutorecoveryWorkflow.workflowIdFor(signal.deviationKey),
                Instant.now().minusSeconds(120))
        )
    }

    @Test
    fun `adapter reports NOT_FOUND, RUNNING, COMPLETED and ENDED_WITHOUT_SUCCESS from Temporal`() {
        assertEquals(WorkflowRunState.NOT_FOUND, workflowAdapter.currentState("nothing-here"))

        service.ingestSignal(providedServiceFailure("st-run"))
        awaitStatus("st-run", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        assertEquals(WorkflowRunState.RUNNING, workflowAdapter.currentState("st-run"))

        service.ingestSignal(shortPick("st-done", "ITEM-1"))
        resultOf("st-done")
        assertEquals(WorkflowRunState.COMPLETED, workflowAdapter.currentState("st-done"))

        stub("st-run").terminate("test")
        assertEquals(WorkflowRunState.ENDED_WITHOUT_SUCCESS, workflowAdapter.currentState("st-run"))
    }

    @Test
    fun `stale RECEIVED deviation is reconciled by the scheduled reconciler entry point`() {
        orphanedRow(shortPick("orphan", "ITEM-1"), RecoveryDecision.AUTO_RECOVER)
        assertEquals(WorkflowRunState.NOT_FOUND, workflowAdapter.currentState("orphan"))

        // Invoked directly - no real scheduler delay in tests.
        AutorecoveryReconciler(service, properties).reconcile()

        assertEquals("AUTO_RECOVERED", resultOf("orphan"))
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("orphan").status)
    }

    @Test
    fun `a waiting workflow is never duplicated by reconciliation or resent signals`() {
        service.ingestSignal(providedServiceFailure("wait-1"))
        awaitStatus("wait-1", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        val runId = stub("wait-1").describe().execution.runId

        assertEquals(ReconciliationOutcome.ALREADY_RUNNING, service.reconcile("wait-1"))
        assertTrue(service.ingestSignal(providedServiceFailure("wait-1")).duplicate)
        assertFalse(workflowAdapter.restartAfterFailure(AutorecoveryInput("wait-1", RecoveryDecision.MANUAL, "r")))
        assertFalse(workflowAdapter.startIfAbsent(AutorecoveryInput("wait-1", RecoveryDecision.MANUAL, "r")))

        assertEquals(runId, stub("wait-1").describe().execution.runId)
        service.submitManualResolution("wait-1", ManualResolution("rcmp-agent", "done"))
        assertEquals("MANUALLY_RESOLVED", resultOf("wait-1"))
    }

    @Test
    fun `a completed workflow is never restarted`() {
        service.ingestSignal(shortPick("done-1", "ITEM-1"))
        assertEquals("AUTO_RECOVERED", resultOf("done-1"))

        assertEquals(ReconciliationOutcome.ALREADY_RESOLVED, service.reconcile("done-1"))
        assertFalse(workflowAdapter.restartAfterFailure(AutorecoveryInput("done-1", RecoveryDecision.AUTO_RECOVER, "r")))
        verify(rebook, times(1)).rebook(any(), any(), any(), any())
    }

    @Test
    fun `workflow that FAILED after exhausting Activity retries is restarted once, as MANUAL, then resolved`() {
        // Technical failure every time: the bounded retries (5 attempts) run out -> workflow FAILED.
        doThrow(IllegalStateException("Fulfillment Options API unavailable"))
            .whenever(fulfillmentOptions).findAlternative(any(), any(), any(), any())
        service.ingestSignal(shortPick("fail-1", "ITEM-1"))
        assertThrows<WorkflowFailedException> { resultOf("fail-1") }
        assertEquals(WorkflowRunState.ENDED_WITHOUT_SUCCESS, workflowAdapter.currentState("fail-1"))
        assertEquals(RecoveryStatus.AUTO_RECOVERING, deviations.rows.getValue("fail-1").status)
        verify(fulfillmentOptions, times(5)).findAlternative(any(), any(), any(), any())

        // Two concurrent reconcilers: Temporal lets exactly one restart through.
        val outcomes = listOf(1, 2).parallelStream().map { service.reconcile("fail-1") }.toList()
        assertEquals(1, outcomes.count { it == ReconciliationOutcome.RESTARTED })

        awaitStatus("fail-1", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("fail-1").owner)
        // The restarted run never repeated automation.
        verify(fulfillmentOptions, times(5)).findAlternative(any(), any(), any(), any())
        verify(rebook, never()).rebook(any(), any(), any(), any())

        service.submitManualResolution("fail-1", ManualResolution("rcmp-agent", "rebooked by hand"))
        assertEquals("MANUALLY_RESOLVED", resultOf("fail-1"))
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("fail-1").status)
        assertTrue(deviations.rows.getValue("fail-1").workflowRestarts in 1..2)
    }

    @Test
    fun `crafted AUTO_RECOVER input for a POST_RELEASE deviation is stopped by the Activity guard`() {
        orphanedRow(providedServiceFailure("crafted"), RecoveryDecision.MANUAL)

        // Someone starts the workflow by hand (e.g. Temporal UI) with forbidden input.
        workflowAdapter.startIfAbsent(AutorecoveryInput("crafted", RecoveryDecision.AUTO_RECOVER, "crafted"))
        assertThrows<WorkflowFailedException> { resultOf("crafted") }
        verify(fulfillmentOptions, never()).findAlternative(any(), any(), any(), any())
        verify(rebook, never()).rebook(any(), any(), any(), any())

        // Reconciliation restarts it from the DB row, whose decision is MANUAL.
        assertEquals(ReconciliationOutcome.RESTARTED, service.reconcile("crafted"))
        awaitStatus("crafted", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("crafted").owner)
    }

    @Test
    fun `manual resolution for a terminated workflow returns WorkflowNotRunning, restarts it with the handover reason, then resolves`() {
        service.ingestSignal(providedServiceFailure("h1-1"))
        awaitStatus("h1-1", RecoveryStatus.AWAITING_MANUAL_RESOLUTION)
        val firstRunId = stub("h1-1").describe().execution.runId
        stub("h1-1").terminate("operator terminated it in Temporal UI")

        // H1: the Signal cannot be delivered - a clean result instead of an exception.
        val first = service.submitManualResolution("h1-1", ManualResolution("RCMP", "too early"))
        assertEquals(ManualResolutionResult.WorkflowNotRunning(AutorecoveryWorkflow.workflowIdFor("h1-1")), first)

        // ...and reconciliation was triggered right away: a new run, parked in Workflow.await().
        assertEquals(WorkflowRunState.RUNNING, workflowAdapter.currentState("h1-1"))
        assertTrue(stub("h1-1").describe().execution.runId != firstRunId)

        // H2: the RCMP-owned alert now tells RCMP the case was handed back after a failed run.
        val handover = "Previous workflow run ended without success while AWAITING_MANUAL_RESOLUTION; handed to RCMP"
        val deadline = System.currentTimeMillis() + 5_000
        while (alerts.rows.getValue("h1-1").reason != handover) {
            check(System.currentTimeMillis() < deadline) { "alert reason never updated: ${alerts.rows.getValue("h1-1").reason}" }
            Thread.sleep(20)
        }
        assertEquals(AlertOwner.RCMP, alerts.rows.getValue("h1-1").owner)
        assertEquals(AlertStatus.OPEN, alerts.rows.getValue("h1-1").status)

        // Retrying the manual resolution now reaches the restarted run.
        assertIs<ManualResolutionResult.SignalSent>(service.submitManualResolution("h1-1", ManualResolution("RCMP", "resolved")))
        assertEquals("MANUALLY_RESOLVED", resultOf("h1-1"))
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("h1-1").status)
        assertEquals(1, deviations.rows.getValue("h1-1").workflowRestarts)
    }
}
