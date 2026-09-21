package com.example.payment.adapters.output.solace

import com.example.payment.application.ports.output.PaymentEventPublisherPort
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler
import com.solacesystems.jcsmp.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant

private data class PaymentSucceededEvent(
    val orderId: String,
    val amount: BigDecimal,
    val occurredAt: String = Instant.now().toString()
)

private data class PaymentFailedEvent(
    val orderId: String,
    val attemptNumber: Int,
    val reason: String,
    val occurredAt: String = Instant.now().toString()
)

@Component
class SolacePaymentEventPublisher(
    private val session: JCSMPSession,
    private val objectMapper: ObjectMapper
) : PaymentEventPublisherPort {

    private val log = LoggerFactory.getLogger(SolacePaymentEventPublisher::class.java)

    private val producer = session.getMessageProducer(
        object : JCSMPStreamingPublishCorrelatingEventHandler {
            override fun responseReceivedEx(key: Any?) {}
            override fun handleErrorEx(key: Any?, cause: JCSMPException, timestamp: Long) {
                log.error("Failed to publish Solace message: {}", cause.message, cause)
            }
        }
    )

    private fun send(topicName: String, payload: String) {
        val message = JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java)
        message.text = payload
        producer.send(message, JCSMPFactory.onlyInstance().createTopic(topicName))
        log.info("Published to {}: {}", topicName, payload)
    }

    override fun publishPaymentSucceeded(orderId: String, amount: BigDecimal) =
        send("order/payment/succeeded", objectMapper.writeValueAsString(PaymentSucceededEvent(orderId, amount)))

    override fun publishPaymentFailed(orderId: String, attemptNumber: Int, reason: String) =
        send("order/payment/failed", objectMapper.writeValueAsString(PaymentFailedEvent(orderId, attemptNumber, reason)))
}
