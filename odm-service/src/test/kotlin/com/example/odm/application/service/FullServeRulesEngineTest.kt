package com.example.odm.application.service

import com.example.odm.rules.FullServeDeviationFact
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The real rules in src/main/resources/fullserve/fullserve-rules.drl, through the classpath KieContainer.
class FullServeRulesEngineTest {

    private val engine = FullServeRulesEngine()

    private fun fact(reasonCode: String, alertConfigEnabled: Boolean) =
        FullServeDeviationFact("FSD-1", "FULL_SERVE", "US", "IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT", reasonCode, alertConfigEnabled)

    @Test
    fun `Full Serve is never eligible for auto-recovery, even for stock and short-pick reasons`() {
        for (reason in listOf("NO_STORE_STOCK", "STORE_SHORT_PICK", "NO_AVAILABLE_PICKING_CAPACITY", "FAILED_TO_PICKUP", "DELAY_CAUSED_BY_IKEA")) {
            assertFalse(engine.evaluate(fact(reason, alertConfigEnabled = true)).autoRecoveryEligible, reason)
        }
    }

    @Test
    fun `alerts are scheduled when the market has an enabled alert configuration`() {
        val verdict = engine.evaluate(fact("NO_STORE_STOCK", alertConfigEnabled = true))
        assertTrue(verdict.alertsEnabled)
        assertEquals(
            setOf("Full Serve deviations are not eligible for auto-recovery",
                "Alert configuration enabled for the market - schedule consumer alerts"),
            verdict.ruleNames.toSet()
        )
    }

    @Test
    fun `no alerts when the market has no enabled alert configuration`() {
        assertFalse(engine.evaluate(fact("NO_STORE_STOCK", alertConfigEnabled = false)).alertsEnabled)
    }
}
