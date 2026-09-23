package com.example.odm.adapters.input.web

import com.example.odm.adapters.input.web.dto.DeviationSignalRequest
import com.example.odm.adapters.input.web.dto.DeviationSignalResponse
import com.example.odm.adapters.input.web.dto.ManualResolutionRequest
import com.example.odm.adapters.input.web.dto.RecoveryResponse
import com.example.odm.application.ports.input.DeviationRecoveryUseCase
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.input.ManualResolutionResult
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// Autorecovery endpoints (Scenarios 5/6). Separate from DeviationController, whose
// /deviations/decision contract with order-service is unchanged.
@RestController
@RequestMapping("/deviations")
class DeviationRecoveryController(
    private val recoveryUseCase: DeviationRecoveryUseCase
) {

    // Simulates the upstream OE/fulfilment system reporting a deviation.
    // 202 = accepted and workflow started; 200 = duplicate deviationKey, nothing new started.
    @PostMapping("/signal")
    fun signal(@Valid @RequestBody request: DeviationSignalRequest): ResponseEntity<DeviationSignalResponse> {
        val result = recoveryUseCase.ingestSignal(request.toSignal())
        val d = result.deviation
        val body = DeviationSignalResponse(d.deviationKey, result.duplicate, d.decision.name, d.decisionRule, d.status.name, d.workflowId)
        return ResponseEntity.status(if (result.duplicate) HttpStatus.OK else HttpStatus.ACCEPTED).body(body)
    }

    // RCMP/manual resolution -> Temporal Signal to the waiting workflow.
    @PostMapping("/{deviationKey}/manual-resolution")
    fun manualResolution(
        @PathVariable deviationKey: String,
        @Valid @RequestBody request: ManualResolutionRequest
    ): ResponseEntity<Map<String, String>> =
        when (val result = recoveryUseCase.submitManualResolution(deviationKey, ManualResolution(request.resolvedBy, request.note))) {
            is ManualResolutionResult.SignalSent ->
                ResponseEntity.accepted().body(mapOf("deviationKey" to deviationKey, "workflowId" to result.workflowId, "signal" to "manualResolution"))
            ManualResolutionResult.NotFound ->
                ResponseEntity.notFound().build()
            is ManualResolutionResult.NotAwaitingManualResolution ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf("deviationKey" to deviationKey, "error" to "Deviation is ${result.status}, not AWAITING_MANUAL_RESOLUTION")
                )
            is ManualResolutionResult.WorkflowNotRunning ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf(
                        "deviationKey" to deviationKey,
                        "workflowId" to result.workflowId,
                        "error" to "Workflow ${result.workflowId} is not currently running, so the manual resolution " +
                            "was not delivered. Reconciliation will restart it; retry the manual resolution shortly."
                    )
                )
        }

    @GetMapping("/{deviationKey}/recovery")
    fun recovery(@PathVariable deviationKey: String): ResponseEntity<RecoveryResponse> =
        recoveryUseCase.findRecovery(deviationKey)
            ?.let { ResponseEntity.ok(RecoveryResponse.from(it)) }
            ?: ResponseEntity.notFound().build()

    // Controller-local: only applies to this controller, never to DeviationController.
    // DeviationSignal's invariants (e.g. picked >= requested is not a short pick) -> 400.
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidSignal(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
