package com.example.odm.application.ports.input

import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationType
import java.util.UUID

interface DeviationDecisionUseCase {

    // The single authoritative, idempotent decision entry point Temporal calls.
    fun decide(orderId: String, type: DeviationType, attemptNumber: Int, reason: String): Decision

    fun findById(id: UUID): Deviation?

    fun findByOrderId(orderId: String): List<Deviation>
}
