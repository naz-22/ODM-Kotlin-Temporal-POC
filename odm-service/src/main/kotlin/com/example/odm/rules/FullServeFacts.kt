package com.example.odm.rules

// Fact for the Full-Serve rules (fullserve/fullserve-rules.drl in the separate fullserveKBase).
// alertConfigEnabled is the market's alert configuration, resolved from odm.fullserve.alerts.*
// before evaluation, so the rules reason about it as plain data - the autorecovery pattern.
class FullServeDeviationFact(
    val deviationId: String,
    val serviceType: String,
    val market: String,
    val deviationType: String,
    val reasonCode: String,
    val alertConfigEnabled: Boolean
)

// Rules write their verdicts here. Every fired rule's name is kept so the decision is
// explainable from the deviation row.
class FullServeRuleOutcome {
    var autoRecoveryEligible: Boolean? = null
    var alertsEnabled: Boolean? = null
    val firedRules: MutableList<String> = mutableListOf()
}
