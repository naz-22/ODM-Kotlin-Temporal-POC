package com.example.odm.application.service

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.input.ManualResolutionResult
import com.example.odm.application.ports.output.AutorecoveryWorkflowPort
import com.example.odm.application.ports.output.WorkflowRunState
import com.example.odm.configuration.AutoRecoveryProperties
import com.example.odm.domain.model.AlertOwner
import com.example.odm.domain.model.AlertStatus
import com.example.odm.domain.model.DeviationSignal
import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.RecoveryResolution
import com.example.odm.domain.model.RecoveryStatus
import com.example.odm.domain.model.ReleaseStage
import com.example.odm.support.InMemoryRecoveryAlertRepository
import com.example.odm.support.InMemoryRecoveryDeviationRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Real Drools + real policy config; persistence and the Temporal client are test doubles.
class DeviationRecoveryServiceTest {

    // Mimics the Temporal server behaviour verified against 1.31: one run per workflow ID;
    // startIfAbsent (REJECT_DUPLICATE) only when no run exists; restartAfterFailure
    // (ALLOW_DUPLICATE_FAILED_ONLY) only when the latest run ended without success.
    private class RecordingWorkflowPort : AutorecoveryWorkflowPort {
        val started = mutableListOf<AutorecoveryInput>()
        val restarted = mutableListOf<AutorecoveryInput>()
        val signals = mutableListOf<Pair<String, ManualResolution>>()
        val states = mutableMapOf<String, WorkflowRunState>()
        override fun startIfAbsent(input: AutorecoveryInput): Boolean {
            if (currentState(input.deviationKey) != WorkflowRunState.NOT_FOUND) return false
            started += input
            states[input.deviationKey] = WorkflowRunState.RUNNING
            return true
        }
        override fun restartAfterFailure(input: AutorecoveryInput): Boolean {
            if (currentState(input.deviationKey) !in setOf(WorkflowRunState.NOT_FOUND, WorkflowRunState.ENDED_WITHOUT_SUCCESS)) return false
            restarted += input
            states[input.deviationKey] = WorkflowRunState.RUNNING
            return true
        }
        override fun currentState(deviationKey: String) = states[deviationKey] ?: WorkflowRunState.NOT_FOUND
        // Like Temporal: a Signal is only delivered to a RUNNING workflow.
        override fun signalManualResolution(deviationKey: String, resolution: ManualResolution): Boolean {
            if (currentState(deviationKey) != WorkflowRunState.RUNNING) return false
            signals += deviationKey to resolution
            return true
        }
    }

    private val deviations = InMemoryRecoveryDeviationRepository()
    private val alerts = InMemoryRecoveryAlertRepository()
    private val workflowPort = RecordingWorkflowPort()
    private val properties = AutoRecoveryProperties(
        mapOf(RecoveryDeviationType.SHORT_PICK to setOf("US", "IN")),
        AutoRecoveryProperties.Reconciliation(maxWorkflowRestarts = 2)
    )
    private val service = DeviationRecoveryService(deviations, alerts, AutorecoveryDecisionEngine(), properties, workflowPort)

    private fun shortPick(key: String = "sp-1", market: String = "US") = DeviationSignal(
        key, "order-1", RecoveryDeviationType.SHORT_PICK, ReleaseStage.PRE_RELEASE, market,
        "ITEM-1", requestedQuantity = 5, pickedQuantity = 3, reason = "Only 3 of 5 picked"
    )

    private fun providedServiceFailure(key: String = "psf-1") = DeviationSignal(
        key, "order-2", RecoveryDeviationType.PROVIDED_SERVICE_FAILURE, ReleaseStage.POST_RELEASE, "US",
        null, null, null, "Last-mile carrier failed delivery"
    )

    @Test
    fun `pre-release short pick in an enabled market decides AUTO_RECOVER and starts the workflow`() {
        val result = service.ingestSignal(shortPick())

        assertFalse(result.duplicate)
        assertEquals(RecoveryDecision.AUTO_RECOVER, result.deviation.decision)
        assertTrue(result.deviation.autoRecoveryEnabled)
        assertEquals(2, result.deviation.shortQuantity)
        assertEquals("autorecovery-sp-1", result.deviation.workflowId)
        assertEquals(RecoveryDecision.AUTO_RECOVER, workflowPort.started.single().decision)
    }

    @Test
    fun `pre-release short pick in a market without auto recovery decides MANUAL`() {
        val result = service.ingestSignal(shortPick(market = "GB"))

        assertFalse(result.deviation.autoRecoveryEnabled)
        assertEquals(RecoveryDecision.MANUAL, result.deviation.decision)
    }

    @Test
    fun `post-release provided service failure decides MANUAL`() {
        val result = service.ingestSignal(providedServiceFailure())

        assertEquals(RecoveryDecision.MANUAL, result.deviation.decision)
        assertEquals(RecoveryDecision.MANUAL, workflowPort.started.single().decision)
    }

    @Test
    fun `duplicate signal returns the existing deviation and never starts a second workflow`() {
        service.ingestSignal(shortPick())
        val second = service.ingestSignal(shortPick())

        assertTrue(second.duplicate)
        assertEquals(1, deviations.rows.size)
        assertEquals(1, workflowPort.started.size)
    }

    @Test
    fun `duplicate signal after the workflow progressed does not even attempt a start`() {
        service.ingestSignal(providedServiceFailure())
        service.requestManualResolution("psf-1", "manual")
        workflowPort.started.clear()

        assertTrue(service.ingestSignal(providedServiceFailure()).duplicate)
        assertTrue(workflowPort.started.isEmpty())
    }

    @Test
    fun `a signal that is not actually a short pick is rejected`() {
        assertThrows<IllegalArgumentException> {
            DeviationSignal("bad", "order-1", RecoveryDeviationType.SHORT_PICK, ReleaseStage.PRE_RELEASE, "US",
                "ITEM-1", requestedQuantity = 3, pickedQuantity = 3, reason = "nothing missing")
        }
    }

    @Test
    fun `successful auto recovery closes the alert`() {
        service.ingestSignal(shortPick())
        service.startAutoRecovery("sp-1")
        assertEquals(AlertStatus.OPEN, alerts.rows.getValue("sp-1").status)
        assertEquals(AlertOwner.AUTORECOVERY, alerts.rows.getValue("sp-1").owner)

        service.closeRecovery("sp-1", RecoveryResolution.AUTO_RECOVERED, "rebooked")

        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("sp-1").status)
        assertEquals(RecoveryStatus.RESOLVED, deviations.rows.getValue("sp-1").status)
        assertEquals(RecoveryResolution.AUTO_RECOVERED, deviations.rows.getValue("sp-1").resolution)
    }

    @Test
    fun `failed auto recovery hands the same alert to RCMP`() {
        service.ingestSignal(shortPick())
        service.startAutoRecovery("sp-1")
        service.requestManualResolution("sp-1", "no alternative")

        val alert = alerts.rows.getValue("sp-1")
        assertEquals(AlertOwner.RCMP, alert.owner)
        assertEquals(AlertStatus.OPEN, alert.status)
        assertEquals(RecoveryStatus.AWAITING_MANUAL_RESOLUTION, deviations.rows.getValue("sp-1").status)
    }

    @Test
    fun `manual resolution is only signalled while the deviation awaits it, and closes the alert`() {
        service.ingestSignal(providedServiceFailure())
        val tooEarly = service.submitManualResolution("psf-1", ManualResolution("rcmp-agent", "done"))
        assertIs<ManualResolutionResult.NotAwaitingManualResolution>(tooEarly)
        assertTrue(workflowPort.signals.isEmpty())

        service.requestManualResolution("psf-1", "Drools decided MANUAL")
        assertEquals(AlertStatus.OPEN, alerts.rows.getValue("psf-1").status)
        val sent = service.submitManualResolution("psf-1", ManualResolution("rcmp-agent", "Redelivery booked"))
        assertIs<ManualResolutionResult.SignalSent>(sent)
        assertEquals("psf-1", workflowPort.signals.single().first)

        // What the workflow does after the signal wakes it:
        service.closeRecovery("psf-1", RecoveryResolution.MANUALLY_RESOLVED, "Resolved by rcmp-agent")
        assertEquals(AlertStatus.CLOSED, alerts.rows.getValue("psf-1").status)
        assertEquals(RecoveryResolution.MANUALLY_RESOLVED, deviations.rows.getValue("psf-1").resolution)
    }

    @Test
    fun `manual resolution for an unknown deviation is NotFound`() {
        assertEquals(ManualResolutionResult.NotFound, service.submitManualResolution("nope", ManualResolution("a", "b")))
    }

    @Test
    fun `activity-facing operations are idempotent under Temporal retries`() {
        service.ingestSignal(shortPick())
        service.startAutoRecovery("sp-1")
        service.startAutoRecovery("sp-1")
        service.closeRecovery("sp-1", RecoveryResolution.AUTO_RECOVERED, "rebooked")
        service.closeRecovery("sp-1", RecoveryResolution.AUTO_RECOVERED, "rebooked")

        assertEquals(RecoveryStatus.RESOLVED, deviations.rows.getValue("sp-1").status)
        assertEquals(1, alerts.rows.size)
    }

    // --- Fix 3: POST_RELEASE hard invariant -----------------------------------------

    @Test
    fun `Drools returning AUTO_RECOVER for POST_RELEASE is overridden to MANUAL`() {
        // Simulates a future DRL mistake: the engine is mocked to return the forbidden verdict.
        val brokenRules: AutorecoveryDecisionEngine = mock()
        whenever(brokenRules.decide(any())).thenReturn(RecoveryVerdict(RecoveryDecision.AUTO_RECOVER, "broken rule"))
        val guarded = DeviationRecoveryService(deviations, alerts, brokenRules, properties, workflowPort)

        val result = guarded.ingestSignal(providedServiceFailure())

        assertEquals(RecoveryDecision.MANUAL, result.deviation.decision)
        assertEquals("POST_RELEASE invariant: never auto-recovered (overrode rule 'broken rule')", result.deviation.decisionRule)
        assertEquals(RecoveryDecision.MANUAL, workflowPort.started.single().decision)
    }

    @Test
    fun `Drools AUTO_RECOVER for PRE_RELEASE is kept`() {
        val rules: AutorecoveryDecisionEngine = mock()
        whenever(rules.decide(any())).thenReturn(RecoveryVerdict(RecoveryDecision.AUTO_RECOVER, "some rule"))

        val result = DeviationRecoveryService(deviations, alerts, rules, properties, workflowPort).ingestSignal(shortPick())

        assertEquals(RecoveryDecision.AUTO_RECOVER, result.deviation.decision)
        assertEquals("some rule", result.deviation.decisionRule)
    }

    // --- Fix 1 / Fix 2: reconciliation ----------------------------------------------

    // A committed row whose workflow start never happened (ODM crashed after the commit).
    private fun orphanedRow(signal: DeviationSignal, decision: RecoveryDecision, ageSeconds: Long = 120) =
        RecoveryDeviation.received(
            signal, true, decision, "rule", "autorecovery-${signal.deviationKey}", Instant.now().minusSeconds(ageSeconds)
        ).also { deviations.insertIfAbsent(it) }

    private fun staleCutoff() = Instant.now().minusSeconds(30)

    @Test
    fun `stale RECEIVED deviation with no workflow is started with its original decision`() {
        orphanedRow(shortPick("orphan"), RecoveryDecision.AUTO_RECOVER)

        val outcomes = service.reconcileStale(staleCutoff())

        assertEquals(ReconciliationOutcome.STARTED, outcomes["orphan"])
        val input = workflowPort.started.single()
        assertEquals(RecoveryDecision.AUTO_RECOVER, input.decision)
        assertNull(input.manualReason)
    }

    @Test
    fun `a fresh RECEIVED deviation is not touched by the sweep`() {
        orphanedRow(shortPick("fresh"), RecoveryDecision.AUTO_RECOVER, ageSeconds = 1)

        assertTrue(service.reconcileStale(staleCutoff()).isEmpty())
        assertTrue(workflowPort.started.isEmpty())
    }

    @Test
    fun `a running workflow is never started again`() {
        orphanedRow(shortPick("running"), RecoveryDecision.AUTO_RECOVER)
        workflowPort.states["running"] = WorkflowRunState.RUNNING

        assertEquals(ReconciliationOutcome.ALREADY_RUNNING, service.reconcileStale(staleCutoff())["running"])
        assertTrue(workflowPort.started.isEmpty())
        assertTrue(workflowPort.restarted.isEmpty())
    }

    @Test
    fun `resolved deviations are ignored and a completed workflow is never restarted`() {
        orphanedRow(shortPick("done"), RecoveryDecision.AUTO_RECOVER)
        service.startAutoRecovery("done")
        service.closeRecovery("done", RecoveryResolution.AUTO_RECOVERED, "ok")
        workflowPort.states["done"] = WorkflowRunState.COMPLETED
        assertTrue(service.reconcileStale(staleCutoff()).isEmpty())
        assertEquals(ReconciliationOutcome.ALREADY_RESOLVED, service.reconcile("done"))

        // Unresolved row but COMPLETED workflow (row changed outside the workflow): still no restart.
        orphanedRow(shortPick("odd"), RecoveryDecision.AUTO_RECOVER)
        workflowPort.states["odd"] = WorkflowRunState.COMPLETED
        assertEquals(ReconciliationOutcome.COMPLETED_NOT_RESTARTED, service.reconcile("odd"))
        assertTrue(workflowPort.started.isEmpty() && workflowPort.restarted.isEmpty())
    }

    @Test
    fun `failed run that had started automation is restarted as MANUAL, never re-running automation`() {
        orphanedRow(shortPick("failed-auto"), RecoveryDecision.AUTO_RECOVER)
        service.startAutoRecovery("failed-auto") // -> AUTO_RECOVERING, then the run failed
        workflowPort.states["failed-auto"] = WorkflowRunState.ENDED_WITHOUT_SUCCESS

        assertEquals(ReconciliationOutcome.RESTARTED, service.reconcile("failed-auto"))

        val input = workflowPort.restarted.single()
        assertEquals(RecoveryDecision.MANUAL, input.decision)
        assertEquals("Previous workflow run ended without success while AUTO_RECOVERING; handed to RCMP", input.manualReason)
        assertEquals(1, deviations.rows.getValue("failed-auto").workflowRestarts)
    }

    @Test
    fun `failed run that never got past RECEIVED is restarted with the original decision`() {
        orphanedRow(shortPick("failed-early"), RecoveryDecision.AUTO_RECOVER)
        workflowPort.states["failed-early"] = WorkflowRunState.ENDED_WITHOUT_SUCCESS

        assertEquals(ReconciliationOutcome.RESTARTED, service.reconcile("failed-early"))
        assertEquals(RecoveryDecision.AUTO_RECOVER, workflowPort.restarted.single().decision)
    }

    @Test
    fun `restarts are bounded by max-workflow-restarts`() {
        orphanedRow(providedServiceFailure("flaky"), RecoveryDecision.MANUAL)
        repeat(2) {
            workflowPort.states["flaky"] = WorkflowRunState.ENDED_WITHOUT_SUCCESS
            assertEquals(ReconciliationOutcome.RESTARTED, service.reconcile("flaky"))
        }
        workflowPort.states["flaky"] = WorkflowRunState.ENDED_WITHOUT_SUCCESS

        assertEquals(ReconciliationOutcome.RESTART_LIMIT_REACHED, service.reconcile("flaky"))
        assertEquals(2, workflowPort.restarted.size)
    }

    @Test
    fun `a resent signal repairs a workflow that never started`() {
        orphanedRow(shortPick("resend"), RecoveryDecision.AUTO_RECOVER)

        val result = service.ingestSignal(shortPick("resend"))

        assertTrue(result.duplicate)
        assertEquals("resend", workflowPort.started.single().deviationKey)
    }

    @Test
    fun `one failing deviation does not stop the sweep for the others`() {
        orphanedRow(shortPick("a"), RecoveryDecision.AUTO_RECOVER)
        orphanedRow(shortPick("b"), RecoveryDecision.AUTO_RECOVER)
        val flakyPort = object : AutorecoveryWorkflowPort by workflowPort {
            override fun currentState(deviationKey: String) =
                if (deviationKey == "a") throw IllegalStateException("Temporal unavailable") else workflowPort.currentState(deviationKey)
        }
        val sweeping = DeviationRecoveryService(deviations, alerts, AutorecoveryDecisionEngine(), properties, flakyPort)

        val outcomes = sweeping.reconcileStale(staleCutoff())

        assertEquals(mapOf("b" to ReconciliationOutcome.STARTED), outcomes)
    }

    // --- H1 / H2 ---------------------------------------------------------------------

    @Test
    fun `manual resolution for a workflow that is not running returns WorkflowNotRunning and triggers reconciliation`() {
        service.ingestSignal(providedServiceFailure())
        service.requestManualResolution("psf-1", "Drools decided MANUAL")
        workflowPort.states["psf-1"] = WorkflowRunState.ENDED_WITHOUT_SUCCESS // e.g. terminated in Temporal UI

        val result = service.submitManualResolution("psf-1", ManualResolution("RCMP", "done"))

        assertEquals(ManualResolutionResult.WorkflowNotRunning("autorecovery-psf-1"), result)
        assertTrue(workflowPort.signals.isEmpty())
        // Reconciliation ran immediately: restarted as MANUAL with the handover reason.
        assertEquals(RecoveryDecision.MANUAL, workflowPort.restarted.single().decision)
        assertEquals(RecoveryStatus.AWAITING_MANUAL_RESOLUTION, deviations.rows.getValue("psf-1").status)
    }

    @Test
    fun `an OPEN RCMP-owned alert receives the new handover reason without changing owner`() {
        service.ingestSignal(providedServiceFailure())
        service.requestManualResolution("psf-1", "Drools decided MANUAL")
        val before = alerts.rows.getValue("psf-1")
        assertEquals(AlertOwner.RCMP, before.owner)

        val handover = "Previous workflow run ended without success while AWAITING_MANUAL_RESOLUTION; handed to RCMP"
        service.requestManualResolution("psf-1", handover)

        val after = alerts.rows.getValue("psf-1")
        assertEquals(handover, after.reason)
        assertEquals(AlertOwner.RCMP, after.owner)
        assertEquals(AlertStatus.OPEN, after.status)
        assertEquals(before.id, after.id)
    }

    @Test
    fun `a closed alert's reason can never be changed`() {
        service.ingestSignal(providedServiceFailure())
        service.requestManualResolution("psf-1", "Drools decided MANUAL")
        val closed = alerts.rows.getValue("psf-1").closed(Instant.now())

        assertThrows<IllegalStateException> { closed.withReason("anything") }
    }
}
