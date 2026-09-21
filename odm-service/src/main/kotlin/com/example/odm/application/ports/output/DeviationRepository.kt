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

    // Race-safe create for the (orderId, type, attemptNumber) key: the synchronous
    // decide() path and the asynchronous Solace consumer path can both try to create
    // the very first row for the same key concurrently. If a row for that key does
    // not exist, inserts `deviation` and returns it; if a concurrent caller already
    // won that race, returns the row THEY persisted instead - the loser's data is
    // discarded, never a second row and never an overwrite of the winner.
    fun createIfAbsent(deviation: Deviation): Deviation
}
