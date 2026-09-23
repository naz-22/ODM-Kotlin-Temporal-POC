package com.example.odm.adapters.input.solace

import com.example.odm.adapters.output.solace.DEVIATION_RESOLVED_TOPIC
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
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// DEMO ONLY - stands in for a downstream system (e.g. customer notification, reporting) that
// subscribes to DeviationResolved. It only logs what it receives: no business logic, no writes.
// It lives in odm-service purely for convenience, but uses its OWN Solace session, so the broker
// sees it as a separate client - the event really travels ODM -> Solace -> this consumer.
// Switch off with odm.demo.deviation-resolved-consumer.enabled=false.
@Component
@ConditionalOnProperty(prefix = "odm.demo.deviation-resolved-consumer", name = ["enabled"], havingValue = "true")
class DeviationResolvedDemoConsumer(
    private val springJCSMPFactory: SpringJCSMPFactory,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(DeviationResolvedDemoConsumer::class.java)
    private lateinit var session: JCSMPSession

    @PostConstruct
    fun start() {
        session = springJCSMPFactory.createSession()
        session.connect()
        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(DEVIATION_RESOLVED_TOPIC))
        session.getMessageConsumer(object : XMLMessageListener {
            override fun onReceive(message: BytesXMLMessage) {
                try {
                    if (message !is TextMessage) return
                    val event = objectMapper.readTree(message.text)
                    log.info(
                        "Received DeviationResolved event: deviationKey={}, resolution={}, resolvedBy={}, eventId={}",
                        event.path("deviationKey").asString(), event.path("resolution").asString(),
                        event.path("resolvedBy").asString(), event.path("eventId").asString()
                    )
                } catch (e: Exception) {
                    log.error("Could not read DeviationResolved event", e)
                }
            }

            override fun onException(exception: JCSMPException) {
                log.error("Demo DeviationResolved consumer exception", exception)
            }
        }).start()
        log.info("Demo downstream consumer subscribed to {}", DEVIATION_RESOLVED_TOPIC)
    }

    @PreDestroy
    fun stop() {
        if (::session.isInitialized) session.closeSession()
    }
}
