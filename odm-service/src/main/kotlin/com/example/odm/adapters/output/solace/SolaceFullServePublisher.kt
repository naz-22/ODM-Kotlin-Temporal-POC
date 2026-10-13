package com.example.odm.adapters.output.solace

import com.example.odm.application.ports.output.FullServePublisherPort
import com.solacesystems.jcsmp.DeliveryMode
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.SpringJCSMPFactory
import com.solacesystems.jcsmp.TextMessage
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Publishes Full-Serve notifications as persistent (guaranteed) messages and waits for the
// broker's acknowledgement before returning. A NACK or a missing acknowledgement throws, so the
// calling Activity is retried by Temporal - unlike the direct publishers elsewhere in this POC,
// "returned normally" here means the broker accepted the message.
// Uses its own session: a JCSMP session has one producer, and this one needs its own ack handler.
@Component
class SolaceFullServePublisher(
    springJCSMPFactory: SpringJCSMPFactory,
    private val objectMapper: ObjectMapper
) : FullServePublisherPort {

    private val log = LoggerFactory.getLogger(SolaceFullServePublisher::class.java)

    private val session: JCSMPSession = springJCSMPFactory.createSession().also { it.connect() }
    private val pendingAcks = ConcurrentHashMap<Any, CompletableFuture<Unit>>()

    private val producer = session.getMessageProducer(object : JCSMPStreamingPublishCorrelatingEventHandler {
        override fun responseReceivedEx(key: Any?) {
            key?.let { pendingAcks.remove(it)?.complete(Unit) }
        }

        override fun handleErrorEx(key: Any?, cause: JCSMPException, timestamp: Long) {
            key?.let { pendingAcks.remove(it)?.completeExceptionally(cause) }
                ?: log.error("Solace publish error without correlation key: {}", cause.message, cause)
        }
    })

    override fun publish(destination: String, payload: Map<String, Any?>) {
        val json = objectMapper.writeValueAsString(payload)
        val correlationKey = UUID.randomUUID().toString()
        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java).apply {
            text = json
            deliveryMode = DeliveryMode.PERSISTENT
            setCorrelationKey(correlationKey)
        }
        val ack = CompletableFuture<Unit>()
        pendingAcks[correlationKey] = ack
        try {
            producer.send(message, JCSMPFactory.onlyInstance().createTopic(destination))
            ack.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            pendingAcks.remove(correlationKey)
        }
        log.info("Published to {} (broker acknowledged): {}", destination, json)
    }

    @PreDestroy
    fun close() = session.closeSession()

    private companion object {
        const val ACK_TIMEOUT_SECONDS = 5L
    }
}
