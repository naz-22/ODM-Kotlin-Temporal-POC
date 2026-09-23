package com.example.odm.application.service

import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.rules.AutorecoveryDeviationFact
import com.example.odm.rules.RecoveryDecisionHolder
import org.kie.api.KieServices
import org.kie.api.runtime.KieContainer
import org.springframework.stereotype.Component

data class RecoveryVerdict(val decision: RecoveryDecision, val ruleName: String)

// Drools evaluation for the autorecovery policy only. Uses its own ksession
// (autorecoveryKSession) so it never shares rules with DroolsDecisionEngine.
@Component
class AutorecoveryDecisionEngine {

    private val kieContainer: KieContainer = KieServices.get().kieClasspathContainer

    fun decide(fact: AutorecoveryDeviationFact): RecoveryVerdict {
        val session = kieContainer.newKieSession("autorecoveryKSession")
            ?: error("autorecoveryKSession not found - check META-INF/kmodule.xml")
        val holder = RecoveryDecisionHolder()
        try {
            session.setGlobal("decisionHolder", holder)
            session.insert(fact)
            session.fireAllRules()
        } finally {
            session.dispose()
        }

        val decision = holder.decision
            ?: throw IllegalStateException("No autorecovery rule fired for deviation ${fact.deviationKey}")
        return RecoveryVerdict(decision, holder.ruleName ?: "unknown")
    }
}
