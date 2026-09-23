package com.example.odm.application.ports.output

import com.example.odm.domain.model.DeviationResolvedEvent

// Output port toward Solace: ODM announces that a recovery deviation was resolved.
// Asynchronous notification for downstream consumers only - not part of orchestration.
interface DeviationEventPublisherPort {

    fun publishDeviationResolved(event: DeviationResolvedEvent)
}
