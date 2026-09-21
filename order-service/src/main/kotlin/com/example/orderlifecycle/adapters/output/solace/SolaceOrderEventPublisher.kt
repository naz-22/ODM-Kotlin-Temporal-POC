package com.example.orderlifecycle.adapters.output.solace

import com.example.orderlifecycle.application.ports.output.OrderEventPublisherPort
import tools.jackson.databind.ObjectMapper
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

// Topic this service publishes meaningful business events to. Order Service does
// NOT publish per-field/DB-status-change noise - only the one event that other
// services genuinely care about: an order has been confirmed.
private const val ORDER_CONFIRMED_TOPIC = "order/confirmed"

private data class OrderConfirmedEvent(
    val orderId: String,
    val customerId: String,
    val occurredAt: String = Instant.now().toString()
)

@Component
class SolaceOrderEventPublisher(
    private val session: JCSMPSession,
    private val objectMapper: ObjectMapper
) : OrderEventPublisherPort {

    private val log = LoggerFactory.getLogger(SolaceOrderEventPublisher::class.java)

    private val producer = session.getMessageProducer(
        object : JCSMPStreamingPublishCorrelatingEventHandler {
            override fun responseReceivedEx(key: Any?) {}
            override fun handleErrorEx(key: Any?, cause: com.solacesystems.jcsmp.JCSMPException, timestamp: Long) {
                log.error("Failed to publish Solace message: {}", cause.message, cause)
            }
        }
    )

    override fun publishOrderConfirmed(orderId: String, customerId: String) {
        val payload = objectMapper.writeValueAsString(OrderConfirmedEvent(orderId, customerId))

        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java)
        message.text = payload

        val topic = JCSMPFactory.onlyInstance().createTopic(ORDER_CONFIRMED_TOPIC)
        producer.send(message, topic)

        log.info("Published to {}: {}", ORDER_CONFIRMED_TOPIC, payload)
    }
}
