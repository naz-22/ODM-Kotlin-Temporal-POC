package com.example.odm.application.service

import com.example.odm.domain.model.Decision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Exercises the real Drools rules in src/main/resources/rules/deviation-rules.drl
// through the classpath KieContainer - no mocking of Drools itself, per the brief's
// "do not fake Drools" requirement.
class DroolsDecisionEngineTest {

    private val engine = DroolsDecisionEngine()

    @Test
    fun `inventory unavailable always resolves to FAIL_ORDER`() {
        val decision = engine.decideForInventoryUnavailable("order-1", "out of stock")
        assertEquals(Decision.FAIL_ORDER, decision)
    }

    @Test
    fun `payment failure with attempts remaining resolves to RETRY`() {
        val decision = engine.decideForPaymentFailed("order-2", attemptNumber = 1, maximumAttempts = 3, reason = "declined")
        assertEquals(Decision.RETRY, decision)
    }

    @Test
    fun `payment failure on the last allowed attempt still retries`() {
        val decision = engine.decideForPaymentFailed("order-3", attemptNumber = 2, maximumAttempts = 3, reason = "declined")
        assertEquals(Decision.RETRY, decision)
    }

    @Test
    fun `payment failure once attempts are exhausted resolves to COMPENSATE`() {
        val decision = engine.decideForPaymentFailed("order-4", attemptNumber = 3, maximumAttempts = 3, reason = "declined")
        assertEquals(Decision.COMPENSATE, decision)
    }
}
