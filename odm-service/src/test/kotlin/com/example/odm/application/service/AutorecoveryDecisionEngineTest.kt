package com.example.odm.application.service

import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.RecoveryDeviationType.PROVIDED_SERVICE_FAILURE
import com.example.odm.domain.model.RecoveryDeviationType.SHORT_PICK
import com.example.odm.domain.model.ReleaseStage
import com.example.odm.domain.model.ReleaseStage.POST_RELEASE
import com.example.odm.domain.model.ReleaseStage.PRE_RELEASE
import com.example.odm.rules.AutorecoveryDeviationFact
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Real Drools evaluation of autorecovery/autorecovery-rules.drl (autorecoveryKSession).
class AutorecoveryDecisionEngineTest {

    private val engine = AutorecoveryDecisionEngine()

    private fun decide(type: RecoveryDeviationType, stage: ReleaseStage, enabled: Boolean) =
        engine.decide(AutorecoveryDeviationFact("dev-1", type, stage, "US", enabled))

    @Test
    fun `pre-release short pick with auto recovery enabled resolves to AUTO_RECOVER`() {
        val verdict = decide(SHORT_PICK, PRE_RELEASE, enabled = true)
        assertEquals(RecoveryDecision.AUTO_RECOVER, verdict.decision)
        assertEquals("Pre-release short pick with auto recovery enabled - auto recover", verdict.ruleName)
    }

    @Test
    fun `pre-release short pick with auto recovery disabled resolves to MANUAL`() {
        assertEquals(RecoveryDecision.MANUAL, decide(SHORT_PICK, PRE_RELEASE, enabled = false).decision)
    }

    @Test
    fun `post-release provided service failure resolves to MANUAL`() {
        val verdict = decide(PROVIDED_SERVICE_FAILURE, POST_RELEASE, enabled = false)
        assertEquals(RecoveryDecision.MANUAL, verdict.decision)
        assertEquals("Post-release deviation - manual", verdict.ruleName)
    }

    @Test
    fun `post-release is manual even when auto recovery is enabled for the market`() {
        assertEquals(RecoveryDecision.MANUAL, decide(SHORT_PICK, POST_RELEASE, enabled = true).decision)
    }

    @Test
    fun `pre-release type without an automated procedure resolves to MANUAL`() {
        assertEquals(RecoveryDecision.MANUAL, decide(PROVIDED_SERVICE_FAILURE, PRE_RELEASE, enabled = true).decision)
    }

    @Test
    fun `existing order-lifecycle rules are unaffected by the new kbase`() {
        assertEquals(
            com.example.odm.domain.model.Decision.FAIL_ORDER,
            DroolsDecisionEngine().decideForInventoryUnavailable("order-1", "no stock")
        )
    }
}
