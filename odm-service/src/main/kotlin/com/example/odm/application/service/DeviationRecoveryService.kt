package com.example.odm.application.service

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.DeviationRecoveryUseCase
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.input.ManualResolutionResult
import com.example.odm.application.ports.input.RecoveryRecord
import com.example.odm.application.ports.input.SignalIngestionResult
import com.example.odm.application.ports.output.AutorecoveryWorkflowPort
import com.example.odm.application.ports.output.DeviationEventPublisherPort
import com.example.odm.application.ports.output.WorkflowRunState
import com.example.odm.application.ports.output.RecoveryAlertRepository
import com.example.odm.application.ports.output.RecoveryDeviationRepository
import com.example.odm.configuration.AutoRecoveryProperties
import com.example.odm.domain.model.AlertOwner
import com.example.odm.domain.model.AlertStatus
import com.example.odm.domain.model.DeviationResolvedEvent
import com.example.odm.domain.model.DeviationSignal
import com.example.odm.domain.model.RecoveryAlert
import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviation
import com.example.odm.domain.model.RecoveryResolution
import com.example.odm.domain.model.RecoveryStatus
import com.example.odm.rules.AutorecoveryDeviationFact
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// ODM's deviation processor for Scenarios 5/6. Two groups of operations:
//  - use cases called by the REST adapter (ingestSignal, submitManualResolution, findRecovery)
//  - state changes called by the Temporal Activities (startAutoRecovery, requestManualResolution,
//    closeRecovery). Each is idempotent, because Temporal may retry an Activity.
// What one reconciliation attempt did (logged, and asserted in tests).
enum class ReconciliationOutcome {
    ALREADY_RESOLVED, ALREADY_RUNNING, COMPLETED_NOT_RESTARTED,
    STARTED, RESTARTED, START_REJECTED, RESTART_LIMIT_REACHED
}

@Service
class DeviationRecoveryService(
    private val deviationRepository: RecoveryDeviationRepository,
    private val alertRepository: RecoveryAlertRepository,
    private val decisionEngine: AutorecoveryDecisionEngine,
    private val autoRecoveryProperties: AutoRecoveryProperties,
    private val workflowPort: AutorecoveryWorkflowPort,
    private val eventPublisher: DeviationEventPublisherPort
) : DeviationRecoveryUseCase {

    private val log = LoggerFactory.getLogger(DeviationRecoveryService::class.java)

    // Intentionally NOT @Transactional: the row insert commits on its own (inside the
    // persistence adapter) BEFORE the workflow starts, so the workflow's first
    // Activity can never run against a row that is later rolled back.
    override fun ingestSignal(signal: DeviationSignal): SignalIngestionResult {
        val existing = deviationRepository.findByKey(signal.deviationKey)
        if (existing != null) {
            log.info("Duplicate deviation signal {} (status={}) - no new decision", signal.deviationKey, existing.status)
            reconcileQuietly(existing.deviationKey)
            return SignalIngestionResult(existing, duplicate = true)
        }

        val autoRecoveryEnabled = autoRecoveryProperties.isAutoRecoveryEnabled(signal.type, signal.market)
        val verdict = enforceReleaseStageInvariant(
            signal,
            decisionEngine.decide(
                AutorecoveryDeviationFact(
                    signal.deviationKey, signal.type, signal.releaseStage, signal.market, autoRecoveryEnabled
                )
            )
        )
        val fresh = RecoveryDeviation.received(
            signal, autoRecoveryEnabled, verdict.decision, verdict.ruleName,
            AutorecoveryWorkflow.workflowIdFor(signal.deviationKey), Instant.now()
        )

        if (!deviationRepository.insertIfAbsent(fresh)) {
            // Lost a race with a concurrent identical signal - converge on the winner's row.
            val winner = deviationRepository.findByKey(signal.deviationKey)
                ?: error("insertIfAbsent reported a conflict but no row exists for ${signal.deviationKey}")
            reconcileQuietly(winner.deviationKey)
            return SignalIngestionResult(winner, duplicate = true)
        }

        log.info("Deviation {} ({} / {} / {}) -> {} by rule '{}'", fresh.deviationKey, fresh.type,
            fresh.releaseStage, fresh.market, fresh.decision, fresh.decisionRule)
        if (workflowPort.startIfAbsent(inputFor(fresh))) {
            log.info("Started workflow {} on {}", fresh.workflowId, AutorecoveryWorkflow.TASK_QUEUE)
        }
        return SignalIngestionResult(fresh, duplicate = false)
    }

    // Hard invariant on top of Drools: a POST_RELEASE deviation is never auto-recovered.
    // If the (editable) rules ever say otherwise, fail safe to MANUAL - RCMP still gets the
    // case - and record why. RecoveryDeviation's own init block would reject the combination.
    private fun enforceReleaseStageInvariant(signal: DeviationSignal, verdict: RecoveryVerdict): RecoveryVerdict {
        if (verdict.decision != RecoveryDecision.AUTO_RECOVER || signal.releaseStage.allowsAutoRecovery) return verdict
        log.error("Drools rule '{}' returned AUTO_RECOVER for {} deviation {} - overridden to MANUAL",
            verdict.ruleName, signal.releaseStage, signal.deviationKey)
        return RecoveryVerdict(
            RecoveryDecision.MANUAL,
            "${signal.releaseStage} invariant: never auto-recovered (overrode rule '${verdict.ruleName}')"
        )
    }

    // --- Reconciliation (duplicate signals + AutorecoveryReconciler) ----------------
    //
    // Temporal is the authority on the workflow; this only reacts to what it reports.
    // Order matters: describe the workflow FIRST, then re-read the row. Once a run is
    // closed no Activity can change the row any more, so the status read afterwards
    // reflects what that run actually finished.
    fun reconcile(deviationKey: String): ReconciliationOutcome {
        val state = workflowPort.currentState(deviationKey)
        val deviation = load(deviationKey)
        if (deviation.status == RecoveryStatus.RESOLVED) return ReconciliationOutcome.ALREADY_RESOLVED

        return when (state) {
            WorkflowRunState.RUNNING -> ReconciliationOutcome.ALREADY_RUNNING

            WorkflowRunState.COMPLETED -> {
                // Never restart a completed workflow. (Completion always resolves the row
                // first, so this would mean the row was changed outside the workflow.)
                log.warn("Workflow {} COMPLETED but deviation is {} - not restarting", deviation.workflowId, deviation.status)
                ReconciliationOutcome.COMPLETED_NOT_RESTARTED
            }

            // Never started (e.g. ODM crashed after the DB commit) - or its history was
            // already removed by Temporal retention. Normal REJECT_DUPLICATE start.
            WorkflowRunState.NOT_FOUND ->
                if (workflowPort.startIfAbsent(inputFor(deviation))) {
                    log.info("Reconciler started missing workflow {} ({})", deviation.workflowId, deviation.status)
                    ReconciliationOutcome.STARTED
                } else ReconciliationOutcome.START_REJECTED

            WorkflowRunState.ENDED_WITHOUT_SUCCESS -> {
                val max = autoRecoveryProperties.reconciliation.maxWorkflowRestarts
                if (!deviationRepository.tryRecordWorkflowRestart(deviationKey, max)) {
                    log.error("Workflow {} ended without success and reached {} restarts - needs operator attention",
                        deviation.workflowId, max)
                    ReconciliationOutcome.RESTART_LIMIT_REACHED
                } else if (workflowPort.restartAfterFailure(inputFor(deviation))) {
                    log.warn("Reconciler restarted workflow {} after unsuccessful run ({})", deviation.workflowId, deviation.status)
                    ReconciliationOutcome.RESTARTED
                } else ReconciliationOutcome.START_REJECTED // someone else restarted it first
            }
        }
    }

    // A duplicate signal is also a chance to repair a missing or failed workflow, but the
    // duplicate itself is still answered normally if Temporal is unreachable right now -
    // the background reconciler will try again.
    private fun reconcileQuietly(deviationKey: String) {
        try {
            reconcile(deviationKey)
        } catch (e: Exception) {
            log.warn("Could not reconcile {} while handling a duplicate signal: {}", deviationKey, e.message)
        }
    }

    // Periodic sweep. Each deviation is independent: one failure (e.g. Temporal briefly
    // unavailable) is logged and the rest are still reconciled.
    fun reconcileStale(cutoff: Instant): Map<String, ReconciliationOutcome> =
        deviationRepository.findUnresolvedCreatedBefore(cutoff).mapNotNull { deviation ->
            try {
                deviation.deviationKey to reconcile(deviation.deviationKey)
            } catch (e: Exception) {
                log.error("Reconciliation of {} failed - will retry next cycle", deviation.deviationKey, e)
                null
            }
        }.toMap()

    // What a (re)started run should do, based on how far the deviation already got:
    //  - RECEIVED: nothing happened yet, so run exactly what was decided.
    //  - AUTO_RECOVERING / AWAITING_MANUAL_RESOLUTION: a previous run already started
    //    work (possibly a rebook). Never repeat automation - hand the case to RCMP.
    private fun inputFor(deviation: RecoveryDeviation): AutorecoveryInput =
        if (deviation.status == RecoveryStatus.RECEIVED) {
            AutorecoveryInput(deviation.deviationKey, deviation.decision, deviation.decisionRule)
        } else {
            AutorecoveryInput(
                deviation.deviationKey, RecoveryDecision.MANUAL, deviation.decisionRule,
                manualReason = "Previous workflow run ended without success while ${deviation.status}; handed to RCMP"
            )
        }

    override fun submitManualResolution(deviationKey: String, resolution: ManualResolution): ManualResolutionResult {
        val deviation = deviationRepository.findByKey(deviationKey) ?: return ManualResolutionResult.NotFound
        if (deviation.status != RecoveryStatus.AWAITING_MANUAL_RESOLUTION) {
            return ManualResolutionResult.NotAwaitingManualResolution(deviation.status)
        }
        // A genuine Temporal Signal - the workflow is parked in Workflow.await() and
        // wakes because of this. Nothing polls the database.
        if (!workflowPort.signalManualResolution(deviationKey, resolution)) {
            // The run ended without success. Trigger reconciliation now instead of waiting for
            // the next sweep; the restarted run parks in Workflow.await() again.
            log.warn("Manual resolution for {} not delivered: workflow {} is not running - reconciling",
                deviationKey, deviation.workflowId)
            reconcileQuietly(deviationKey)
            return ManualResolutionResult.WorkflowNotRunning(deviation.workflowId)
        }
        log.info("Manual resolution signal sent to {} by {}", deviation.workflowId, resolution.resolvedBy)
        return ManualResolutionResult.SignalSent(deviation.workflowId)
    }

    override fun findRecovery(deviationKey: String): RecoveryRecord? =
        deviationRepository.findByKey(deviationKey)?.let {
            RecoveryRecord(it, alertRepository.findByDeviationKey(deviationKey))
        }

    // --- Called from Temporal Activities -----------------------------------------

    @Transactional
    fun startAutoRecovery(deviationKey: String) {
        val deviation = load(deviationKey)
        openAlertIfAbsent(deviationKey, AlertOwner.AUTORECOVERY, "Automated recovery started: ${deviation.reason}")
        if (deviation.status == RecoveryStatus.RECEIVED) {
            deviationRepository.save(deviation.moveTo(RecoveryStatus.AUTO_RECOVERING))
        }
    }

    @Transactional
    fun requestManualResolution(deviationKey: String, reason: String) {
        val deviation = load(deviationKey)
        val alert = openAlertIfAbsent(deviationKey, AlertOwner.RCMP, reason)
        if (alert.owner != AlertOwner.RCMP) {
            // Automated recovery gave up - hand the same alert over to RCMP.
            alertRepository.save(alert.reassignedTo(AlertOwner.RCMP, reason))
        } else if (alert.status == AlertStatus.OPEN && alert.reason != reason) {
            // Already RCMP's (e.g. a restarted run after a failure): keep the owner, but record
            // the new reason so RCMP can see the case was handed back to them.
            alertRepository.save(alert.withReason(reason))
        }
        if (deviation.status != RecoveryStatus.AWAITING_MANUAL_RESOLUTION) {
            deviationRepository.save(deviation.moveTo(RecoveryStatus.AWAITING_MANUAL_RESOLUTION))
        }
    }

    @Transactional
    fun closeRecovery(deviationKey: String, resolution: RecoveryResolution, note: String) {
        val now = Instant.now()
        val deviation = load(deviationKey)
        if (deviation.status != RecoveryStatus.RESOLVED) {
            deviationRepository.save(deviation.resolved(resolution, note, now))
        }
        alertRepository.findByDeviationKey(deviationKey)
            ?.takeIf { it.status == AlertStatus.OPEN }
            ?.let { alertRepository.save(it.closed(now)) }
        log.info("Deviation {} resolved: {} ({}) - alert CLOSED", deviationKey, resolution, note)
    }

    // Publishes DeviationResolved once the deviation is RESOLVED and committed. Called by the
    // CloseRecovery Activity right after closeRecovery(), outside its transaction, so an event is
    // never announced for a resolution that could still roll back.
    // At-least-once, de-duplicated: a Temporal retry after a successful publish sees the marker and
    // skips; a failed publish throws (marker unset) so Temporal retries it. Only a crash between
    // publish and marker can repeat it - with the same deterministic eventId.
    fun publishResolvedEventOnce(deviationKey: String) {
        val deviation = load(deviationKey)
        if (deviation.status != RecoveryStatus.RESOLVED || deviation.resolvedEventPublishedAt != null) return
        eventPublisher.publishDeviationResolved(DeviationResolvedEvent.from(deviation))
        deviationRepository.markResolvedEventPublished(deviationKey, Instant.now())
    }

    fun load(deviationKey: String): RecoveryDeviation =
        deviationRepository.findByKey(deviationKey) ?: error("Unknown recovery deviation $deviationKey")

    private fun openAlertIfAbsent(deviationKey: String, owner: AlertOwner, reason: String): RecoveryAlert =
        alertRepository.findByDeviationKey(deviationKey)
            ?: alertRepository.save(RecoveryAlert.open(deviationKey, owner, reason, Instant.now()))
}
