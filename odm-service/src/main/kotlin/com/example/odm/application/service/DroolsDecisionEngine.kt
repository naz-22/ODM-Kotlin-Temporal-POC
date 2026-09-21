package com.example.odm.application.service

import com.example.odm.domain.model.Decision
import com.example.odm.rules.DecisionHolder
import com.example.odm.rules.InventoryUnavailableDeviationFact
import com.example.odm.rules.PaymentFailedDeviationFact
import org.kie.api.KieServices
import org.kie.api.runtime.KieContainer
import org.springframework.stereotype.Component

// The one place Drools is touched. Everything above this (the application service,
// the REST/Solace adapters) only ever sees the plain Decision enum coming back -
// nobody else needs to know Drools is involved at all.
@Component
class DroolsDecisionEngine {

    private val kieContainer: KieContainer = KieServices.get().kieClasspathContainer
    // Builds the KieContainer once at startup from src/main/resources/rules/deviation-rules.drl
    // (declared via META-INF/kmodule.xml) - a genuine Drools rule evaluation, not a fake stand-in.

    fun decideForInventoryUnavailable(orderId: String, reason: String): Decision =
        evaluate { session -> session.insert(InventoryUnavailableDeviationFact(orderId, reason)) }

    fun decideForPaymentFailed(orderId: String, attemptNumber: Int, maximumAttempts: Int, reason: String): Decision =
        evaluate { session ->
            session.insert(PaymentFailedDeviationFact(orderId, attemptNumber, maximumAttempts, reason))
        }

    private fun evaluate(insertFacts: (org.kie.api.runtime.KieSession) -> Unit): Decision {
        val session = kieContainer.newKieSession("deviationKSession")
        val holder = DecisionHolder()
        try {
            session.setGlobal("decisionHolder", holder)
            insertFacts(session)
            session.fireAllRules()
        } finally {
            session.dispose()
            // Always dispose: a KieSession is not meant to be reused/kept open across calls.
        }

        val decision = holder.decision
            ?: throw IllegalStateException("No Drools rule fired - no decision was produced")
        return Decision.valueOf(decision)
    }
}
