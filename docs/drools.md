# Drools in this POC

## 1. What Drools is doing

`odm-service` uses Drools to answer exactly one question, synchronously:

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

Two small, plain Kotlin classes in `com.example.odm.rules`:

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

`DroolsDecisionEngine` (`com.example.odm.application.service`) is the only class that touches
Drools:

1. At startup, `KieServices.get().kieClasspathContainer` builds a `KieContainer` from
   `src/main/resources/rules/deviation-rules.drl`, discovered via
   `src/main/resources/META-INF/kmodule.xml`.
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
  `getDeviationDecision` and does a plain `when` on the three-value `Decision` enum. Temporal
  never asks *why* a decision was made; it just obeys it.

## 7. How ODM and Temporal interact

Temporal (via the `getDeviationDecision` Activity in order-service) calls ODM's synchronous
`POST /deviations/decision` endpoint and blocks on the HTTP response - no polling, no Signals,
no callbacks. See [architecture.md](architecture.md) and [solace.md](solace.md) for how this
endpoint stays authoritative and idempotent even though ODM *also* passively observes the
same failures via Solace.
