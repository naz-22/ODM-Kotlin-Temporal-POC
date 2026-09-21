package com.example.inventory.adapters.output.solace

import com.example.inventory.application.ports.output.InventoryEventPublisherPort
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

private data class InventoryEvent(
    val orderId: String,
    val productId: String,
    val quantity: Int,
    val occurredAt: String = Instant.now().toString()
)

@Component
class SolaceInventoryEventPublisher(
    private val session: JCSMPSession,
    private val objectMapper: ObjectMapper
) : InventoryEventPublisherPort {

    private val log = LoggerFactory.getLogger(SolaceInventoryEventPublisher::class.java)

    private val producer = session.getMessageProducer(
        object : JCSMPStreamingPublishCorrelatingEventHandler {
            override fun responseReceivedEx(key: Any?) {}
            override fun handleErrorEx(key: Any?, cause: JCSMPException, timestamp: Long) {
                log.error("Failed to publish Solace message: {}", cause.message, cause)
            }
        }
    )

    private fun publish(topicName: String, event: InventoryEvent) {
        val payload = objectMapper.writeValueAsString(event)
        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java)
        message.text = payload
        producer.send(message, JCSMPFactory.onlyInstance().createTopic(topicName))
        log.info("Published to {}: {}", topicName, payload)
    }

    override fun publishInventoryReserved(orderId: String, productId: String, quantity: Int) =
        publish("order/inventory/reserved", InventoryEvent(orderId, productId, quantity))

    override fun publishInventoryUnavailable(orderId: String, productId: String, quantity: Int) =
        publish("order/inventory/unavailable", InventoryEvent(orderId, productId, quantity))

    override fun publishInventoryReleased(orderId: String, productId: String, quantity: Int) =
        publish("order/inventory/released", InventoryEvent(orderId, productId, quantity))
}
