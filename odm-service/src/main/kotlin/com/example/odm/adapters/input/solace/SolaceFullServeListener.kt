package com.example.odm.adapters.input.solace

import com.example.odm.application.ports.input.FullServeDeviationUseCase
import com.example.odm.application.ports.input.FullServeSignalMessage
import com.example.odm.application.ports.input.FullServeUpdateMessage
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.configuration.FullServeProperties
import com.solacesystems.jcsmp.BytesXMLMessage
import com.solacesystems.jcsmp.ConsumerFlowProperties
import com.solacesystems.jcsmp.EndpointProperties
import com.solacesystems.jcsmp.FlowReceiver
import com.solacesystems.jcsmp.JCSMPErrorResponseException
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx
import com.solacesystems.jcsmp.JCSMPException
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPProperties
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.SpringJCSMPFactory
import com.solacesystems.jcsmp.TextMessage
import com.solacesystems.jcsmp.XMLMessage.Outcome
import com.solacesystems.jcsmp.XMLMessageListener
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

// Solace ingestion boundary for Full Serve: consumes ODM.DEVIATION.SIGNAL.Q and ODM.DEVIATION.UPDATE.Q
// with client acknowledgement and hands each message to the ODM ingress (FullServeDeviationUseCase),
// which makes exactly one Temporal call. A message is settled only once that call has an outcome:
//   ACCEPTED - started, signalled, or deliberately ignored (duplicate, late, unknown): done.
//   REJECTED - malformed or failed contract validation: redelivery cannot fix it (DMQ if configured).
//   FAILED   - Temporal/DB unreachable or "retry later": the broker redelivers it.
@Component
@ConditionalOnProperty(prefix = "odm.fullserve.solace", name = ["listener-enabled"], havingValue = "true")
class SolaceFullServeListener(
    private val springJCSMPFactory: SpringJCSMPFactory,
    private val objectMapper: ObjectMapper,
    private val fullServe: FullServeDeviationUseCase,
    private val properties: FullServeProperties
) {

    private val log = LoggerFactory.getLogger(SolaceFullServeListener::class.java)
    private lateinit var session: JCSMPSession
    private val flows = mutableListOf<FlowReceiver>()

    @PostConstruct
    fun start() {
        session = springJCSMPFactory.createSession()
        session.connect()
        val destinations = properties.destinations
        flows += consume(destinations.signalQueue, destinations.signalTopic) { json ->
            when (fullServe.receiveSignal(objectMapper.readValue(json, FullServeSignalMessage::class.java)).outcome) {
                IntakeOutcome.ACCEPTED, IntakeOutcome.IGNORED_DUPLICATE -> Outcome.ACCEPTED
                IntakeOutcome.REJECTED_INVALID -> Outcome.REJECTED
                IntakeOutcome.RETRY_LATER -> Outcome.FAILED
            }
        }
        flows += consume(destinations.updateQueue, destinations.updateTopic) { json ->
            when (fullServe.receiveUpdate(objectMapper.readValue(json, FullServeUpdateMessage::class.java)).outcome) {
                UpdateOutcome.SIGNALLED, UpdateOutcome.NO_OPEN_DEVIATION,
                UpdateOutcome.STALE_DEVIATION_ID, UpdateOutcome.WORKFLOW_NOT_RUNNING -> Outcome.ACCEPTED
                UpdateOutcome.REJECTED_INVALID -> Outcome.REJECTED
            }
        }
    }

    private fun consume(queueName: String, topic: String, handle: (String) -> Outcome): FlowReceiver {
        val queue = JCSMPFactory.onlyInstance().createQueue(queueName)
        if (properties.solace.provisionQueues) provision(queueName, topic)

        val flowProperties = ConsumerFlowProperties().apply {
            endpoint = queue
            ackMode = JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT
            addRequiredSettlementOutcomes(Outcome.FAILED, Outcome.REJECTED)
        }
        val flow = session.createFlow(object : XMLMessageListener {
            override fun onReceive(message: BytesXMLMessage) {
                val outcome = try {
                    handle(textOf(message))
                } catch (e: JacksonException) {
                    log.warn("Unreadable message on {} rejected: {}", queueName, e.originalMessage)
                    Outcome.REJECTED
                } catch (e: Exception) {
                    log.error("Technical failure handling message from {} - leaving it for redelivery", queueName, e)
                    Outcome.FAILED
                }
                try {
                    message.settle(outcome)
                } catch (e: JCSMPException) {
                    // Unsettled messages are redelivered after the flow reconnects - never lost.
                    log.error("Could not settle message from {} as {}", queueName, outcome, e)
                }
            }

            override fun onException(exception: JCSMPException) {
                log.error("Solace flow exception on {}", queueName, exception)
            }
        }, flowProperties)
        flow.start()
        log.info("Consuming {} (client acknowledgement)", queueName)
        return flow
    }

    // Local broker only - in a shared environment the queues and their subscriptions are
    // provisioned by the platform team and this stays off.
    private fun provision(queueName: String, topic: String) {
        val queue = JCSMPFactory.onlyInstance().createQueue(queueName)
        val endpoint = EndpointProperties().apply {
            accessType = EndpointProperties.ACCESSTYPE_EXCLUSIVE
            permission = EndpointProperties.PERMISSION_CONSUME
        }
        session.provision(queue, endpoint, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS.toLong())
        try {
            session.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic(topic), JCSMPSession.WAIT_FOR_CONFIRM)
        } catch (e: JCSMPErrorResponseException) {
            if (e.subcodeEx != JCSMPErrorResponseSubcodeEx.SUBSCRIPTION_ALREADY_PRESENT) throw e
        }
        log.info("Provisioned queue {} subscribed to {}", queueName, topic)
    }

    // OE/OEP may publish JSON as a text message or as a binary attachment (e.g. via Solace REST).
    private fun textOf(message: BytesXMLMessage): String = when {
        message is TextMessage -> message.text
        message.hasAttachment() -> ByteArray(message.attachmentContentLength).also { message.readAttachmentBytes(it) }.decodeToString()
        else -> message.bytes.decodeToString()
    }

    @PreDestroy
    fun stop() {
        flows.forEach { it.close() }
        if (::session.isInitialized) session.closeSession()
    }
}
