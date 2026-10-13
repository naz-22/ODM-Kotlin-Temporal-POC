package com.example.odm.application.ports.output

// Outbound publications of the Full-Serve lifecycle: O360 deviation created/closed, consumer
// alerts and alert closures. Throws on a technical failure so the calling Activity is retried.
interface FullServePublisherPort {

    fun publish(destination: String, payload: Map<String, Any?>)
}
