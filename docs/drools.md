# Drools in this POC

## 1. What Drools is doing

`odm-service` uses Drools for three independent rule sets (see `META-INF/kmodule.xml`). Sections
1-7 cover the order-lifecycle one, which answers exactly one question, synchronously:

> "Given this deviation, what should happen next - `RETRY`, `COMPENSATE`, or `FAIL_ORDER`?"

That's it. Drools does not touch the order lifecycle, does not persist anything, and does
not know about Temporal, REST, or Solace. It receives a small Fact object, evaluates a
handful of rules against it, and produces one of three decisions.

## 2. Why it is being used here

The brief for this POC treats "when do we retry a payment vs. give up and compensate" as a
**business** decision, not a technical one, and wants that decision to be:

- **Explicit and inspectable** - a business analyst should be able to read
  `deviation-rules.drl` and understand the policy without reading Kotlin.
- **Configurable independently of code** - the retry-vs-compensate threshold
  (`payment.max-attempts`) is read from configuration, not hardcoded into a rule.
- **Kept out of Temporal and out of the domain model** - `Order.moveTo()` enforces
  lifecycle *invariants* (you can't skip stages); Drools decides *business policy*
  (how many attempts is reasonable). Mixing the two would make both harder to read.

A small `when { ... }` in Kotlin could technically do the same three-line job. The point of
using a real rules engine here is architectural: it draws a hard boundary between "the rule
that decided this" (Drools, replaceable/auditable) and "the code that enforces this" (Kotlin,
stable). That boundary is what the POC is meant to demonstrate.

## 3. What data becomes a Fact

The order-lifecycle facts are two small, plain Kotlin classes in `com.example.odm.rules`:

```kotlin
class InventoryUnavailableDeviationFact(
    val orderId: String,
    val reason: String
)

class PaymentFailedDeviationFact(
    val orderId: String,
    val attemptNumber: Int,
    val maximumAttempts: Int,
    val failureReason: String
)
```

Nothing about persistence, HTTP, or Solace leaks into these classes - they carry only the
data a rule needs to reason about. Kotlin's generated JavaBean getters (`getAttemptNumber()`,
`getMaximumAttempts()`, ...) are what Drools' pattern matching actually calls.

## 4. How rules are evaluated

`DroolsDecisionEngine` (`com.example.odm.application.service`) is the only class that uses
`deviationKSession`:

1. At startup, `KieServices.get().kieClasspathContainer` builds the classpath `KieContainer`
   (every kbase declared in `src/main/resources/META-INF/kmodule.xml`); this engine only opens
   `deviationKSession`, whose rules are in `src/main/resources/rules/deviation-rules.drl`.
2. For each decision request, it opens a fresh `KieSession`, sets a `DecisionHolder` global,
   inserts **one** Fact, and calls `fireAllRules()`.
3. Whichever rule matches writes its verdict into `decisionHolder.decision`.
4. The session is disposed immediately - sessions are cheap and are never reused across
   requests, which keeps each decision fully independent and easy to reason about.

```drl
rule "Inventory unavailable always fails the order"
    when
        InventoryUnavailableDeviationFact()
    then
        decisionHolder.setDecision("FAIL_ORDER");
end

rule "Payment failed but attempts remain - retry"
    when
        PaymentFailedDeviationFact(attemptNumber < maximumAttempts)
    then
        decisionHolder.setDecision("RETRY");
end

rule "Payment retries exhausted - compensate"
    when
        PaymentFailedDeviationFact(attemptNumber >= maximumAttempts)
    then
        decisionHolder.setDecision("COMPENSATE");
end
```

## 5. What decisions can be returned

`Decision` (`com.example.odm.domain.model`) is a closed set of three outcomes:

| Decision     | Meaning                                                        | Triggered by                                  |
|--------------|-----------------------------------------------------------------|------------------------------------------------|
| `RETRY`      | Try the business operation again                                | Payment failed, attempts remain                |
| `COMPENSATE` | Undo prior side effects and cancel the order                    | Payment failed, attempts exhausted             |
| `FAIL_ORDER` | Stop and mark the order failed - nothing to undo                | Inventory unavailable                          |

## 6. What remains in Kotlin

- **Lifecycle invariants** (`Order.moveTo()` in order-service) - which status can follow
  which. This is a structural rule that should never depend on configuration, so it stays
  in Kotlin, not Drools.
- **Idempotency** (`DeviationApplicationService.decide()`) - finding-or-creating a
  `Deviation` row by `(orderId, type, attemptNumber)` and short-circuiting if a decision was
  already made. This is plumbing, not business policy.
- **The Temporal orchestration loop** (`OrderLifecycleWorkflowImpl`) - it calls
  `getDeviationDecision` and does a plain `when` on the three-value `DeviationDecision` enum. Temporal
  never asks *why* a decision was made; it just obeys it.

## 7. How ODM and Temporal interact

Temporal (via the `getDeviationDecision` Activity in order-service) calls ODM's synchronous
`POST /deviations/decision` endpoint and blocks on the HTTP response - no polling, no Signals,
no callbacks. See [architecture.md](architecture.md) and [solace.md](solace.md) for how this
endpoint stays authoritative and idempotent even though ODM *also* passively observes the
same failures via Solace.

## 8. Autorecovery rules (Scenarios 5 and 6)

A second, independent rule set: `src/main/resources/autorecovery/autorecovery-rules.drl`. It
lives in its own kbase/ksession (`autorecoveryKBase` / `autorecoveryKSession` in
`kmodule.xml`) and is evaluated by `AutorecoveryDecisionEngine`.

The fact is `AutorecoveryDeviationFact(deviationKey, type, releaseStage, market,
autoRecoveryEnabled)`. `autoRecoveryEnabled` is resolved beforehand from
`odm.autorecovery.enabled-markets[...]` in `application.properties`, so the policy stays in
configuration and the decision stays in Drools. The result is `AUTO_RECOVER` or `MANUAL`,
together with the name of the rule that fired. That rule name is stored in
`recovery_deviations.decision_rule`.

| Rule | Condition | Decision |
|------|-----------|----------|
| Pre-release short pick with auto recovery enabled - auto recover | `SHORT_PICK`, `PRE_RELEASE`, enabled | `AUTO_RECOVER` |
| Post-release deviation - manual | `POST_RELEASE` | `MANUAL` |
| Pre-release deviation with auto recovery disabled - manual | `PRE_RELEASE`, not enabled | `MANUAL` |
| Pre-release deviation with no automated recovery procedure - manual | `PRE_RELEASE`, enabled, not `SHORT_PICK` | `MANUAL` |

The rules are mutually exclusive and cover every case, so exactly one fires. See
[autorecovery.md](autorecovery.md).

## 9. Full-Serve rules

A third kbase, `fullserveKBase` / `fullserveKSession`, loaded from
`src/main/resources/fullserve/fullserve-rules.drl` and evaluated by `FullServeRulesEngine`
from the `CheckBusinessRules` Activity. The fact is `FullServeDeviationFact(deviationId,
serviceType, market, deviationType, reasonCode, alertConfigEnabled)`; `alertConfigEnabled` is
resolved beforehand from `odm.fullserve.alerts.enabled-markets`.

| Rule | Condition | Verdict |
|------|-----------|---------|
| Full Serve deviations are not eligible for auto-recovery | `serviceType == FULL_SERVE` | `autoRecoveryEligible = false` |
| Alert configuration enabled for the market - schedule consumer alerts | `alertConfigEnabled` | `alertsEnabled = true` |
| No enabled alert configuration for the market - no consumer alerts | `!alertConfigEnabled` | `alertsEnabled = false` |

The eligibility rule and exactly one of the two alert rules fire (`serviceType` is validated as
`FULL_SERVE` at intake); a missing verdict fails the Activity as non-retryable
(`FullServeRulesDefect`). The rule content is a POC assumption. See
[odm-fullserve-walkthrough.md](odm-fullserve-walkthrough.md).
