package com.example.odm.adapters.input.web

import com.example.odm.application.ports.input.FullServeDeviationUseCase
import com.example.odm.application.ports.input.FullServeDeviationView
import com.example.odm.application.ports.input.FullServeSignalMessage
import com.example.odm.application.ports.input.FullServeUpdateMessage
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.IntakeResult
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.application.ports.input.UpdateResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// Demo and support interface for Full Serve. The POST endpoints call the same ingress as the
// Solace queue listener, so they exercise the same validation, duplicate handling and workflow
// start/Signal - only the transport differs. In production OE/OEP publish to Solace instead.
@RestController
@RequestMapping("/fullserve/deviations")
class FullServeDeviationController(
    private val fullServe: FullServeDeviationUseCase
) {

    // 202 accepted (workflow started), 200 duplicate ignored, 400 invalid, 409 retry later.
    @PostMapping("/signals")
    fun signal(@RequestBody message: FullServeSignalMessage): ResponseEntity<IntakeResult> {
        val result = fullServe.receiveSignal(message)
        val status = when (result.outcome) {
            IntakeOutcome.ACCEPTED -> HttpStatus.ACCEPTED
            IntakeOutcome.IGNORED_DUPLICATE -> HttpStatus.OK
            IntakeOutcome.REJECTED_INVALID -> HttpStatus.BAD_REQUEST
            IntakeOutcome.RETRY_LATER -> HttpStatus.CONFLICT
        }
        return ResponseEntity.status(status).body(result)
    }

    // 202 signalled, 400 invalid, 404 no OPEN deviation for the order, 409 stale or workflow not running.
    @PostMapping("/updates")
    fun update(@RequestBody message: FullServeUpdateMessage): ResponseEntity<UpdateResult> {
        val result = fullServe.receiveUpdate(message)
        val status = when (result.outcome) {
            UpdateOutcome.SIGNALLED -> HttpStatus.ACCEPTED
            UpdateOutcome.REJECTED_INVALID -> HttpStatus.BAD_REQUEST
            UpdateOutcome.NO_OPEN_DEVIATION -> HttpStatus.NOT_FOUND
            UpdateOutcome.STALE_DEVIATION_ID, UpdateOutcome.WORKFLOW_NOT_RUNNING -> HttpStatus.CONFLICT
        }
        return ResponseEntity.status(status).body(result)
    }

    @GetMapping("/{deviationId}")
    fun byId(@PathVariable deviationId: String): ResponseEntity<FullServeDeviationView> =
        fullServe.findDeviation(deviationId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @GetMapping
    fun byOrder(@RequestParam orderId: String): List<FullServeDeviationView> = fullServe.findByOrderId(orderId)
}
