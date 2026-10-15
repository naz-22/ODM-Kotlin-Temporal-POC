package com.example.odm.adapters.input.solace

import com.example.odm.application.ports.input.FullServeDeviationUseCase
import com.example.odm.application.ports.input.FullServeDeviationView
import com.example.odm.application.ports.input.FullServeSignalMessage
import com.example.odm.application.ports.input.FullServeUpdateMessage
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.IntakeResult
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.application.ports.input.UpdateResult
import com.example.odm.configuration.FullServeProperties
import com.solacesystems.jcsmp.BytesMessage
import com.solacesystems.jcsmp.BytesXMLMessage
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPProperties
import com.solacesystems.jcsmp.SpringJCSMPFactory
import com.solacesystems.jcsmp.TextMessage
import com.solacesystems.jcsmp.XMLMessage.Outcome
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The listener's per-message path (decode -> ODM ingress -> settlement outcome) without a broker:
// the session is only created in start(), which these tests never call.
class SolaceFullServeListenerTest {

    private val signalJson = """
        {"orderId":"FS-SOLACE-DEBUG-001","source":"OE","serviceType":"FULL_SERVE","market":"US",
         "deviationType":"IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT","reasonCode":"STORE_SHORT_PICK",
         "occurrence":"FULFILMENT","reasonCaused":"NO_STOCK"}
    """.trimIndent()

    private class RecordingUseCase(var intake: IntakeOutcome = IntakeOutcome.ACCEPTED) : FullServeDeviationUseCase {
        val signals = mutableListOf<FullServeSignalMessage>()
        var failWith: RuntimeException? = null

        override fun receiveSignal(message: FullServeSignalMessage): IntakeResult {
            failWith?.let { throw it }
            signals += message
            return IntakeResult(intake, message.orderId, "fullserve-deviation-${message.orderId}")
        }

        override fun receiveUpdate(message: FullServeUpdateMessage) =
            UpdateResult(UpdateOutcome.SIGNALLED, message.orderId, "FSD-1", "fullserve-deviation-${message.orderId}")

        override fun findDeviation(deviationId: String): FullServeDeviationView? = null
        override fun findByOrderId(orderId: String): List<FullServeDeviationView> = emptyList()
    }

    private val useCase = RecordingUseCase()
    private val listener = SolaceFullServeListener(
        SpringJCSMPFactory(JCSMPProperties(), null), jacksonObjectMapper(), useCase, FullServeProperties()
    )
    private val queue = "ODM.DEVIATION.SIGNAL.Q"

    private fun textMessage(text: String): BytesXMLMessage =
        JCSMPFactory.onlyInstance().createMessage(TextMessage::class.java).apply { this.text = text }

    @Test
    fun `a Try Me text message is decoded and handed to the ODM ingress, then acknowledged`() {
        assertEquals(Outcome.ACCEPTED, listener.process(queue, textMessage(signalJson), listener::handleSignal))

        val received = useCase.signals.single()
        assertEquals("FS-SOLACE-DEBUG-001", received.orderId)
        assertEquals("IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT", received.deviationType)
        assertEquals("NO_STOCK", received.reasonCaused)
    }

    @Test
    fun `a binary attachment payload (Solace REST) is decoded the same way`() {
        val message = JCSMPFactory.onlyInstance().createMessage(BytesMessage::class.java)
            .apply { setData(signalJson.toByteArray()) }

        assertEquals(Outcome.ACCEPTED, listener.process(queue, message, listener::handleSignal))
        assertEquals("FS-SOLACE-DEBUG-001", useCase.signals.single().orderId)
    }

    @Test
    fun `intake outcomes map to settlement outcomes`() {
        for ((intake, expected) in listOf(
            IntakeOutcome.ACCEPTED to Outcome.ACCEPTED,
            IntakeOutcome.IGNORED_DUPLICATE to Outcome.ACCEPTED,
            IntakeOutcome.REJECTED_INVALID to Outcome.REJECTED,
            IntakeOutcome.RETRY_LATER to Outcome.FAILED
        )) {
            useCase.intake = intake
            assertEquals(expected, listener.process(queue, textMessage(signalJson), listener::handleSignal), intake.name)
        }
    }

    @Test
    fun `malformed JSON is rejected without reaching the ODM ingress`() {
        assertEquals(Outcome.REJECTED, listener.process(queue, textMessage("{not json"), listener::handleSignal))
        assertTrue(useCase.signals.isEmpty())
    }

    @Test
    fun `a technical failure (e g Temporal unreachable) leaves the message for redelivery`() {
        useCase.failWith = IllegalStateException("UNAVAILABLE: io exception")

        assertEquals(Outcome.FAILED, listener.process(queue, textMessage(signalJson), listener::handleSignal))
    }

    @Test
    fun `an update is decoded and acknowledged once signalled`() {
        val update = """{"orderId":"FS-SOLACE-DEBUG-001","source":"OE","trigger":"COWORKER","resolutionStatus":"RESOLVED"}"""

        assertEquals(Outcome.ACCEPTED, listener.process("ODM.DEVIATION.UPDATE.Q", textMessage(update), listener::handleUpdate))
    }
}
