package com.example.odm.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

// POST_RELEASE is never auto-recovered - enforced by the domain model itself, whatever Drools says.
class RecoveryDeviationInvariantTest {

    private fun deviation(stage: ReleaseStage, decision: RecoveryDecision) = RecoveryDeviation.received(
        DeviationSignal("k-1", "order-1", RecoveryDeviationType.SHORT_PICK, stage, "US", "ITEM-1", 5, 3, "short"),
        autoRecoveryEnabled = true, decision = decision, decisionRule = "r", workflowId = "autorecovery-k-1",
        now = Instant.now()
    )

    @Test
    fun `POST_RELEASE with AUTO_RECOVER cannot be constructed`() {
        val e = assertThrows<IllegalArgumentException> { deviation(ReleaseStage.POST_RELEASE, RecoveryDecision.AUTO_RECOVER) }
        assertEquals("k-1: POST_RELEASE deviations can never be AUTO_RECOVER", e.message)
    }

    @Test
    fun `POST_RELEASE with MANUAL is valid`() {
        assertEquals(RecoveryDecision.MANUAL, deviation(ReleaseStage.POST_RELEASE, RecoveryDecision.MANUAL).decision)
    }

    @Test
    fun `PRE_RELEASE with AUTO_RECOVER is valid`() {
        assertEquals(RecoveryDecision.AUTO_RECOVER, deviation(ReleaseStage.PRE_RELEASE, RecoveryDecision.AUTO_RECOVER).decision)
    }
}
