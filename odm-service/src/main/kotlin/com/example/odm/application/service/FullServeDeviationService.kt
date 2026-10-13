package com.example.odm.application.service

import com.example.odm.application.ports.input.FullServeDeviationUseCase
import com.example.odm.application.ports.input.FullServeDeviationView
import com.example.odm.application.ports.input.FullServeDeviationWorkflow
import com.example.odm.application.ports.input.FullServeSignalMessage
import com.example.odm.application.ports.input.FullServeUpdateMessage
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.IntakeResult
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.application.ports.input.UpdateResult
import com.example.odm.application.ports.output.FullServeDeviationRepository
import com.example.odm.configuration.FullServeProperties
import com.example.odm.domain.model.ClosureTrigger
import com.example.odm.domain.model.FullServeDeviation
import com.example.odm.domain.model.FullServeSignal
import com.example.odm.domain.model.FullServeUpdate
import com.example.odm.domain.model.ResolutionStatus
import com.example.odm.domain.model.SignalSource
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// ODM ingress for Full Serve: validates the payload, checks for an OPEN deviation and starts or
// signals the order's FullServeDeviationWorkflow. Stateless - the workflow owns the lifecycle.
@Service
class FullServeDeviationService(
    private val repository: FullServeDeviationRepository,
    private val workflowClient: WorkflowClient,
    private val properties: FullServeProperties
) : FullServeDeviationUseCase {

    private val log = LoggerFactory.getLogger(FullServeDeviationService::class.java)

    override fun receiveSignal(message: FullServeSignalMessage): IntakeResult {
        val errors = mutableListOf<String>()
        val signal = validateSignal(message, errors)
        if (signal == null) {
            log.warn("Deviation signal rejected for order {}: {}", message.orderId, errors)
            return IntakeResult(IntakeOutcome.REJECTED_INVALID, message.orderId, errors = errors)
        }

        repository.findOpenByOrderId(signal.orderId)?.let { return duplicate(it) }

        val workflowId = FullServeDeviationWorkflow.workflowIdFor(signal.orderId)
        val workflow = workflowClient.newWorkflowStub(
            FullServeDeviationWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(FullServeDeviationWorkflow.TASK_QUEUE)
                // A completed run does not block a later deviation for the same order; a running one does.
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build()
        )
        try {
            WorkflowClient.start(workflow::handle, signal)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            // Temporal allows one running execution per order. If it already holds an OPEN
            // deviation this signal is a duplicate. Otherwise that run is still creating its
            // deviation (a concurrent duplicate) or announcing its closure: ask for redelivery,
            // which then resolves to IGNORED_DUPLICATE or ACCEPTED.
            repository.findOpenByOrderId(signal.orderId)?.let { return duplicate(it) }
            log.info("Workflow {} is running but holds no OPEN deviation yet/any more - retry later", workflowId)
            return IntakeResult(IntakeOutcome.RETRY_LATER, signal.orderId, workflowId)
        }
        log.info("Deviation signal for order {} accepted - started {} on {}", signal.orderId, workflowId, FullServeDeviationWorkflow.TASK_QUEUE)
        return IntakeResult(IntakeOutcome.ACCEPTED, signal.orderId, workflowId)
    }

    override fun receiveUpdate(message: FullServeUpdateMessage): UpdateResult {
        val errors = mutableListOf<String>()
        val update = validateUpdate(message, errors)
        if (update == null) {
            log.warn("Deviation update rejected for order {}: {}", message.orderId, errors)
            return UpdateResult(UpdateOutcome.REJECTED_INVALID, message.orderId, errors = errors)
        }

        // Correlation: the order's OPEN deviation row names the workflow that owns it.
        val open = repository.findOpenByOrderId(update.orderId)
        if (open == null) {
            log.warn("Update {} from {} for order {} has no OPEN deviation (late, duplicate or unknown) - ignored",
                update.trigger, update.source, update.orderId)
            return UpdateResult(UpdateOutcome.NO_OPEN_DEVIATION, update.orderId, update.deviationId)
        }
        if (update.deviationId != null && update.deviationId != open.deviationId) {
            log.warn("Update for order {} names deviation {}, but the OPEN deviation is {} - ignored",
                update.orderId, update.deviationId, open.deviationId)
            return UpdateResult(UpdateOutcome.STALE_DEVIATION_ID, update.orderId, update.deviationId, open.workflowId)
        }

        try {
            // A real Temporal Signal to the running execution - never a new workflow.
            workflowClient.newWorkflowStub(FullServeDeviationWorkflow::class.java, open.workflowId).resolutionUpdate(update)
        } catch (e: WorkflowNotFoundException) {
            log.error("Deviation {} is OPEN but workflow {} is not running - update not delivered, needs operator attention",
                open.deviationId, open.workflowId)
            return UpdateResult(UpdateOutcome.WORKFLOW_NOT_RUNNING, update.orderId, open.deviationId, open.workflowId)
        }
        log.info("Update {} ({}) from {} signalled to {}", update.trigger, update.resolutionStatus, update.source, open.workflowId)
        return UpdateResult(UpdateOutcome.SIGNALLED, update.orderId, open.deviationId, open.workflowId)
    }

    override fun findDeviation(deviationId: String): FullServeDeviationView? =
        repository.findById(deviationId)?.let { FullServeDeviationView(it, repository.findNotifications(deviationId)) }

    override fun findByOrderId(orderId: String): List<FullServeDeviationView> =
        repository.findByOrderId(orderId).map { FullServeDeviationView(it, repository.findNotifications(it.deviationId)) }

    private fun duplicate(open: FullServeDeviation): IntakeResult {
        log.info("Order {} already has OPEN deviation {} - signal ignored", open.orderId, open.deviationId)
        return IntakeResult(IntakeOutcome.IGNORED_DUPLICATE, open.orderId, open.workflowId, open.deviationId)
    }

    // Contract validation: required fields present and from the agreed enumerations. Returns null
    // and fills `errors` if anything is wrong.
    private fun validateSignal(m: FullServeSignalMessage, errors: MutableList<String>): FullServeSignal? {
        val contract = properties.contract
        val orderId = text(m.orderId, "orderId", 100, errors)
        val source = oneOf(m.source, "source", listOf(SignalSource.OE.name, SignalSource.OEP.name), errors)
        val serviceType = oneOf(m.serviceType, "serviceType", listOf(FULL_SERVE), errors)
        val market = text(m.market, "market", 10, errors)?.uppercase()
        val deviationType = oneOf(m.deviationType, "deviationType", contract.deviationTypes, errors)
        val reasonCode = oneOf(m.reasonCode, "reasonCode", contract.reasonCodes, errors)
        val occurrence = oneOf(m.occurrence, "occurrence", contract.occurrences, errors)
        if (m.reasonCaused != null && m.reasonCaused.length > 200) errors += "reasonCaused must be at most 200 characters"
        if (errors.isNotEmpty()) return null
        return FullServeSignal(orderId!!, SignalSource.valueOf(source!!), serviceType!!, market!!,
            deviationType!!, reasonCode!!, occurrence!!, m.reasonCaused?.takeIf { it.isNotBlank() })
    }

    private fun validateUpdate(m: FullServeUpdateMessage, errors: MutableList<String>): FullServeUpdate? {
        val orderId = text(m.orderId, "orderId", 100, errors)
        val source = oneOf(m.source, "source", SignalSource.entries.map { it.name }, errors)
        val trigger = oneOf(m.trigger, "trigger", ClosureTrigger.entries.map { it.name }, errors)
        val status = oneOf(m.resolutionStatus, "resolutionStatus", ResolutionStatus.entries.map { it.name }, errors)
        if (m.note != null && m.note.length > 500) errors += "note must be at most 500 characters"
        if (errors.isNotEmpty()) return null
        return FullServeUpdate(orderId!!, SignalSource.valueOf(source!!), ClosureTrigger.valueOf(trigger!!),
            ResolutionStatus.valueOf(status!!), m.deviationId?.takeIf { it.isNotBlank() }, m.note)
    }

    private fun text(value: String?, field: String, maxLength: Int, errors: MutableList<String>): String? = when {
        value.isNullOrBlank() -> null.also { errors += "$field is required" }
        value.length > maxLength -> null.also { errors += "$field must be at most $maxLength characters" }
        else -> value.trim()
    }

    private fun oneOf(value: String?, field: String, allowed: Collection<String>, errors: MutableList<String>): String? = when {
        value.isNullOrBlank() -> null.also { errors += "$field is required" }
        value !in allowed -> null.also { errors += "$field '$value' is not one of $allowed" }
        else -> value
    }

    private companion object {
        const val FULL_SERVE = "FULL_SERVE"
    }
}
