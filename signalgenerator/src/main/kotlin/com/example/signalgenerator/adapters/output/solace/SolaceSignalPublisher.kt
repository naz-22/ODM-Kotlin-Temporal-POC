package com.example.signalgenerator.adapters.output.solace

import com.example.signalgenerator.application.ports.output.SignalPublisherPort
import com.example.signalgenerator.domain.OrderDeviationSignal
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class SolaceSignalPublisher(
    session: JCSMPSession,
    private val objectMapper: ObjectMapper,
    @Value("\${signalgenerator.solace.topics.order-deviation-signal}")
    private val topic: String
) : SignalPublisherPort {

    private val log = LoggerFactory.getLogger(SolaceSignalPublisher::class.java)

    private val producer = session.getMessageProducer(
        object : JCSMPStreamingPublishCorrelatingEventHandler {
            override fun responseReceivedEx(key: Any?) {}
            override fun handleErrorEx(key: Any?, cause: JCSMPException, timestamp: Long) {
                log.error("Failed to publish Solace message: {}", cause.message, cause)
            }
        }
    )

    override fun publishOrderDeviation(signal: OrderDeviationSignal) {
        val payload = objectMapper.writeValueAsString(signal)
        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java)
        message.text = payload
        producer.send(message, JCSMPFactory.onlyInstance().createTopic(topic))
        log.info("Published to {}: {}", topic, payload)
    }
}
