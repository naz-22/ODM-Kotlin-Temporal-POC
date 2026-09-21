package com.example.odm.adapters.input.solace

import com.example.odm.application.service.DeviationApplicationService
import com.example.odm.domain.model.DeviationType
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

private const val INVENTORY_UNAVAILABLE_TOPIC = "order/inventory/unavailable"
private const val PAYMENT_FAILED_TOPIC = "order/payment/failed"

private data class InventoryUnavailableMessage(val orderId: String, val productId: String, val quantity: Int)
private data class PaymentFailedMessage(val orderId: String, val attemptNumber: Int, val reason: String)

// This is the SECONDARY, asynchronous path described in docs/architecture.md: it
// idempotently records that a deviation-worthy event happened, using the exact same
// (orderId, type, attemptNumber) correlation key the synchronous decision endpoint
// uses. It deliberately does NOT evaluate Drools or make any decision - that stays
// the sole responsibility of DeviationController/DeviationApplicationService.decide(),
// so the two paths can only ever converge, never disagree.
@Component
class SolaceDeviationEventListener(
    private val session: JCSMPSession,
    private val objectMapper: ObjectMapper,
    private val deviationApplicationService: DeviationApplicationService
) {

    private val log = LoggerFactory.getLogger(SolaceDeviationEventListener::class.java)

    @PostConstruct
    fun start() {
        val inventoryTopic = JCSMPFactory.onlyInstance().createTopic(INVENTORY_UNAVAILABLE_TOPIC)
        val paymentTopic = JCSMPFactory.onlyInstance().createTopic(PAYMENT_FAILED_TOPIC)
        session.addSubscription(inventoryTopic)
        session.addSubscription(paymentTopic)

        val consumer = session.getMessageConsumer(object : XMLMessageListener {
            override fun onReceive(message: BytesXMLMessage) {
                try {
                    handle(message)
                } catch (e: Exception) {
                    log.error("Failed to process Solace message from {}", message.destination?.name, e)
                }
            }

            override fun onException(exception: JCSMPException) {
                log.error("Solace consumer exception", exception)
            }
        })

        consumer.start()
        log.info("Subscribed to {} and {}", INVENTORY_UNAVAILABLE_TOPIC, PAYMENT_FAILED_TOPIC)
    }

    private fun handle(message: BytesXMLMessage) {
        if (message !is TextMessage) return
        val payload = message.text

        when (message.destination?.name) {
            INVENTORY_UNAVAILABLE_TOPIC -> {
                val event = objectMapper.readValue(payload, InventoryUnavailableMessage::class.java)
                deviationApplicationService.recordObservedDeviation(
                    event.orderId,
                    DeviationType.INVENTORY_UNAVAILABLE,
                    0,
                    "Inventory unavailable for product ${event.productId}"
                )
            }

            PAYMENT_FAILED_TOPIC -> {
                val event = objectMapper.readValue(payload, PaymentFailedMessage::class.java)
                deviationApplicationService.recordObservedDeviation(
                    event.orderId,
                    DeviationType.PAYMENT_FAILED,
                    event.attemptNumber,
                    event.reason
                )
            }
        }
    }
}
