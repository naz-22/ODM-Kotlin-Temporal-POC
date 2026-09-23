package com.example.odm.rules

import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryDeviationType
import com.example.odm.domain.model.ReleaseStage

// Fact for the autorecovery rules (autorecovery/autorecovery-rules.drl in the separate
// autorecoveryKBase). autoRecoveryEnabled is the (type + market) policy value, resolved
// from configuration before evaluation, so rules reason about it as plain data.
class AutorecoveryDeviationFact(
    val deviationKey: String,
    val type: RecoveryDeviationType,
    val releaseStage: ReleaseStage,
    val market: String,
    val autoRecoveryEnabled: Boolean
)

// Typed result holder (the existing DecisionHolder is String-based and has no rule
// name). ruleName is recorded so every decision is explainable from the DB row.
class RecoveryDecisionHolder {
    var decision: RecoveryDecision? = null
    var ruleName: String? = null
}
