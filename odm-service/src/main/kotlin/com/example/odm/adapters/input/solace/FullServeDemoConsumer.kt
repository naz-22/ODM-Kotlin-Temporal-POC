package com.example.odm.adapters.input.solace

import com.solacesystems.jcsmp.BytesXMLMessage
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.SpringJCSMPFactory
import com.solacesystems.jcsmp.TextMessage
import com.solacesystems.jcsmp.XMLMessageListener
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

// DEMO ONLY - a simulated SPM / Dynamics / O360 subscriber. The real consumer systems are not
// available locally, so this stands in for them: it subscribes to odm/out/deviation/> on its OWN
// Solace session (the broker sees a separate client, so messages really travel ODM -> Solace ->
// here) and keeps the last messages in memory for GET /fullserve/demo/received.
// It proves broker delivery to a subscriber - not delivery to, or processing by, SPM, Dynamics or O360.
@RestController
@ConditionalOnProperty(prefix = "odm.fullserve.solace", name = ["demo-consumer-enabled"], havingValue = "true")
class FullServeDemoConsumer(
    private val springJCSMPFactory: SpringJCSMPFactory,
    private val objectMapper: ObjectMapper
) {

    data class ReceivedMessage(val receivedAt: Instant, val topic: String, val simulatedRecipient: String, val payload: Any?)

    private val log = LoggerFactory.getLogger(FullServeDemoConsumer::class.java)
    private val received = ConcurrentLinkedDeque<ReceivedMessage>()
    private lateinit var session: JCSMPSession

    @PostConstruct
    fun start() {
        session = springJCSMPFactory.createSession()
        session.connect()
        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(OUTBOUND_TOPICS))
        session.getMessageConsumer(object : XMLMessageListener {
            override fun onReceive(message: BytesXMLMessage) {
                if (message !is TextMessage) return
                val topic = message.destination.name
                val entry = ReceivedMessage(Instant.now(), topic, recipientOf(topic), objectMapper.readTree(message.text))
                received.addFirst(entry)
                while (received.size > MAX_KEPT) received.pollLast()
                log.info("[simulated {}] received on {}: {}", entry.simulatedRecipient, topic, message.text)
            }

            override fun onException(exception: JCSMPException) {
                log.error("Simulated Full-Serve consumer exception", exception)
            }
        }).start()
        log.info("Simulated SPM/Dynamics/O360 consumer subscribed to {}", OUTBOUND_TOPICS)
    }

    @GetMapping("/fullserve/demo/received")
    fun received(@RequestParam(required = false) orderId: String?): List<ReceivedMessage> =
        received.filter { orderId == null || it.topic.endsWith("/$orderId") || it.payload.toString().contains("\"$orderId\"") }

    // Which consumer the documented destination is meant for.
    private fun recipientOf(topic: String) = when {
        topic.contains("/alert/SPM/") -> "SPM"
        topic.contains("/alert/DYNAMICS/") -> "DYNAMICS"
        topic.endsWith("/deviation/closed") -> "SPM+DYNAMICS"
        topic.endsWith("/deviation/created") || topic.endsWith("/deviation/lcm-closed") -> "O360"
        else -> "unknown"
    }

    @PreDestroy
    fun stop() {
        if (::session.isInitialized) session.closeSession()
    }

    private companion object {
        const val OUTBOUND_TOPICS = "odm/out/deviation/>"
        const val MAX_KEPT = 200
    }
}
