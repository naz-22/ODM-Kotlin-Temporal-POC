package com.example.odm.adapters.output.solace

import com.example.odm.application.ports.output.DeviationEventPublisherPort
import com.example.odm.domain.model.DeviationResolvedEvent
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// Follows the existing order/<area>/<event> topic convention (order/confirmed,
// order/payment/failed, ...).
const val DEVIATION_RESOLVED_TOPIC = "order/deviation/resolved"

// Same pattern as the other services' Solace publishers: JSON TextMessage on the shared session.
@Component
class SolaceDeviationEventPublisher(
    session: JCSMPSession,
    private val objectMapper: ObjectMapper
) : DeviationEventPublisherPort {

    private val log = LoggerFactory.getLogger(SolaceDeviationEventPublisher::class.java)

    private val producer = session.getMessageProducer(
        object : JCSMPStreamingPublishCorrelatingEventHandler {
            override fun responseReceivedEx(key: Any?) {}
            override fun handleErrorEx(key: Any?, cause: JCSMPException, timestamp: Long) {
                log.error("Failed to publish Solace message: {}", cause.message, cause)
            }
        }
    )

    override fun publishDeviationResolved(event: DeviationResolvedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java)
        message.text = payload
        producer.send(message, JCSMPFactory.onlyInstance().createTopic(DEVIATION_RESOLVED_TOPIC))
        log.info("Published to {}: {}", DEVIATION_RESOLVED_TOPIC, payload)
    }
}
