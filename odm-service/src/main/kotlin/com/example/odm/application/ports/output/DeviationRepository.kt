package com.example.odm.application.ports.output

import com.example.odm.domain.model.Deviation
import com.example.odm.domain.model.DeviationType
import java.util.UUID

interface DeviationRepository {

    fun findById(id: UUID): Deviation?

    fun findByOrderId(orderId: String): List<Deviation>

    // orderId + type + attemptNumber is the idempotency/correlation key shared by
    // both the synchronous decision path and the asynchronous Solace consumer path.
    fun findByOrderIdAndTypeAndAttemptNumber(orderId: String, type: DeviationType, attemptNumber: Int): Deviation?

    fun save(deviation: Deviation): Deviation
}
