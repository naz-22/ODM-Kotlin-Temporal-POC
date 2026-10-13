package com.example.odm.application.service

import com.example.odm.rules.FullServeDeviationFact
import com.example.odm.rules.FullServeRuleOutcome
import org.kie.api.KieServices
import org.kie.api.runtime.KieContainer
import org.springframework.stereotype.Component

data class FullServeRuleVerdict(
    val autoRecoveryEligible: Boolean,
    val alertsEnabled: Boolean,
    val ruleNames: List<String>
)

// Drools evaluation for the Full-Serve rules only, on their own ksession (fullserveKSession).
@Component
class FullServeRulesEngine {

    private val kieContainer: KieContainer = KieServices.get().kieClasspathContainer

    fun evaluate(fact: FullServeDeviationFact): FullServeRuleVerdict {
        val session = kieContainer.newKieSession("fullserveKSession")
            ?: error("fullserveKSession not found - check META-INF/kmodule.xml")
        val outcome = FullServeRuleOutcome()
        try {
            session.setGlobal("outcome", outcome)
            session.insert(fact)
            session.fireAllRules()
        } finally {
            session.dispose()
        }

        // A missing verdict is a rules defect, not a transient failure.
        val alertsEnabled = outcome.alertsEnabled
            ?: throw IllegalStateException("No Full-Serve alert rule fired for deviation ${fact.deviationId}")
        val autoRecoveryEligible = outcome.autoRecoveryEligible
            ?: throw IllegalStateException("No auto-recovery eligibility rule fired for deviation ${fact.deviationId}")
        return FullServeRuleVerdict(autoRecoveryEligible, alertsEnabled, outcome.firedRules.toList())
    }
}
