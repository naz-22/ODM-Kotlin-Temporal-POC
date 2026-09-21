package com.example.odm.adapters.input.web

import com.example.odm.adapters.input.web.dto.DecisionRequest
import com.example.odm.adapters.input.web.dto.DecisionResponse
import com.example.odm.adapters.input.web.dto.DeviationResponse
import com.example.odm.application.ports.input.DeviationDecisionUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/deviations")
class DeviationController(
    private val deviationDecisionUseCase: DeviationDecisionUseCase
) {

    // The synchronous, authoritative, idempotent decision endpoint Temporal calls.
    // It does NOT wait for or depend on the Solace event for the same deviation
    // having been consumed - see docs/architecture.md section on the ODM/Solace race.
    @PostMapping("/decision")
    fun decide(@Valid @RequestBody request: DecisionRequest): DecisionResponse {
        val decision = deviationDecisionUseCase.decide(
            request.orderId, request.type, request.attemptNumber, request.reason
        )
        return DecisionResponse(decision.name)
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ResponseEntity<DeviationResponse> {
        val deviation = deviationDecisionUseCase.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(DeviationResponse.from(deviation))
    }

    @GetMapping("/order/{orderId}")
    fun getByOrderId(@PathVariable orderId: String): List<DeviationResponse> =
        deviationDecisionUseCase.findByOrderId(orderId).map(DeviationResponse::from)
}
