package com.example.notification.adapters.input.solace

import com.example.notification.domain.NotificationRecord
import com.example.notification.domain.NotificationStore
import com.solacesystems.jcsmp.BytesXMLMessage
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.TextMessage
import com.solacesystems.jcsmp.XMLMessageListener
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

private const val ORDER_CONFIRMED_TOPIC = "order/confirmed"

private data class OrderConfirmedMessage(val orderId: String, val customerId: String)

// The one genuine Solace consumer this service exists to demonstrate: order-service
// publishes OrderConfirmed once, and this service reacts to it completely
// independently of the Temporal workflow that triggered it.
@Component
class SolaceOrderConfirmedListener(
    private val session: JCSMPSession,
    private val objectMapper: ObjectMapper,
    private val notificationStore: NotificationStore
) {

    private val log = LoggerFactory.getLogger(SolaceOrderConfirmedListener::class.java)

    @PostConstruct
    fun start() {
        val topic = JCSMPFactory.onlyInstance().createTopic(ORDER_CONFIRMED_TOPIC)
        session.addSubscription(topic)

        val consumer = session.getMessageConsumer(object : XMLMessageListener {
            override fun onReceive(message: BytesXMLMessage) {
                try {
                    handle(message)
                } catch (e: Exception) {
                    log.error("Failed to process OrderConfirmed message", e)
                }
            }

            override fun onException(exception: JCSMPException) {
                log.error("Solace consumer exception", exception)
            }
        })

        consumer.start()
        log.info("Subscribed to {}", ORDER_CONFIRMED_TOPIC)
    }

    private fun handle(message: BytesXMLMessage) {
        if (message !is TextMessage) return
        val event = objectMapper.readValue(message.text, OrderConfirmedMessage::class.java)

        notificationStore.record(
            NotificationRecord(
                orderId = event.orderId,
                customerId = event.customerId,
                message = "Your order ${event.orderId} has been confirmed.",
                receivedAt = Instant.now()
            )
        )
        log.info("Notification processed for orderId={}", event.orderId)
    }
}
