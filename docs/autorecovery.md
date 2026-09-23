# ODM Autorecovery - Scenarios 5 and 6

This is an ODM-only extension. It does not touch the order lifecycle (Scenarios 1-4):
order-service, its `OrderLifecycleWorkflow`, `order-lifecycle-task-queue`,
`POST /deviations/decision`, `deviation-rules.drl`, the `deviations` table and the Solace
listener all behave exactly as before.

```
Upstream OE / fulfilment system            (simulated with curl / Postman)
        |  POST /deviations/signal
        v
ODM  DeviationRecoveryController -> DeviationRecoveryService
        |  1. validate signal (Kotlin invariants)
        |  2. policy: (deviationType + market) -> autoRecoveryEnabled   (application.properties)
        |  3. Drools autorecoveryKSession -> AUTO_RECOVER | MANUAL       (autorecovery-rules.drl)
        |  4. persist recovery_deviations row (idempotent on deviation_key)
        |  5. start Temporal workflow  autorecovery-<deviationKey>  on  odm-autorecovery-queue
        v
Temporal AutorecoveryWorkflow (hosted by odm-service's own worker)
        |
        +-- AUTO_RECOVER: startAutoRecovery -> findFulfillmentOption -> rebookOrder -> closeRecovery
        |                 (no option / rebook failed -> fall through to MANUAL)
        |
        +-- MANUAL:       requestManualResolution (alert OPEN for RCMP)
                          Workflow.await { manualResolution != null }     <- durable wait
                          ... POST /deviations/{key}/manual-resolution -> Temporal Signal ...
                          closeRecovery (alert CLOSED)
```

Solace is not involved in this capability. It remains the event backbone for Scenarios 1-4
and is not used here as an orchestrator.

## Scenario 5 - Short Pick Auto-Recovery

**Business problem.** The warehouse picked fewer units than the order asked for
(`requestedQuantity = 5`, `pickedQuantity = 3`, so 2 units are short). The order is still
**PRE_RELEASE**: it has not been handed to the last mile yet, so it can still be re-planned.

**Upstream signal.** The fulfilment system reports "short pick on order X, item Y, 3 of 5
picked". ODM rejects the signal with HTTP 400 if it is not really a short pick
(`picked >= requested`, or the item or quantities are missing).

**What ODM does.** It looks up the auto-recovery policy for `(SHORT_PICK, market)`, asks
Drools for a decision, stores the deviation and starts the workflow.

**Configuration.** In `odm-service/src/main/resources/application.properties`:

```properties
odm.autorecovery.enabled-markets[SHORT_PICK]=US,IN
```

`SHORT_PICK` is enabled for US and IN. Every other `(type, market)` pair is disabled. For
example, a `GB` short pick is decided `MANUAL`.

**What Drools decides.** `SHORT_PICK + PRE_RELEASE + autoRecoveryEnabled -> AUTO_RECOVER`.

**What Temporal orchestrates.** The workflow runs these steps in order:
1. Open an alert owned by `AUTORECOVERY`.
2. Ask the Fulfillment Options API for an alternative source for the 2 missing units.
3. Call the Rebook API with that option.
4. On success, close the alert and resolve the deviation as `AUTO_RECOVERED`.

If there is no option, or the rebook fails, the same alert is handed to `RCMP` and the
workflow continues on the manual path described in Scenario 6.

**Why PRE_RELEASE can auto-recover.** The order has not left yet. Choosing another source
and rebooking only changes the plan, not something the customer has already received.

**Mock APIs** (deterministic, in-process, in `adapters/output/fulfillment`):

| itemId                | Fulfillment Options             | Rebook      |
|-----------------------|----------------------------------|-------------|
| anything else         | `FO-<itemId>-ALT` at `<market>-ALT-NODE` | success (`RB-<orderId>-<optionId>`) |
| `ITEM-NO-ALTERNATIVE` | no alternative (`null`)          | not called  |
| `ITEM-REBOOK-FAIL`    | option found                     | failure     |

## Scenario 6 - Provided Service Failure / Manual Resolution

**Business problem.** A provided service failed after release. For example, the last-mile
carrier could not deliver. The order is **POST_RELEASE**.

**Upstream signal.** The fulfilment system reports `PROVIDED_SERVICE_FAILURE` at
`POST_RELEASE`. Item and quantities are optional here.

**What Drools decides.** `POST_RELEASE -> MANUAL`, whatever the market configuration says.

**Why POST_RELEASE skips automated recovery.** The order is already in the last mile, so
rebooking the original plan cannot fix what went wrong. A person has to decide on
redelivery, refund or contacting the customer. The workflow therefore **never calls
Fulfillment Options or Rebook** on this path, and tests verify that.

**What Temporal orchestrates.**
1. `requestManualResolution` opens an alert owned by `RCMP` (status `OPEN`) and moves the
   deviation to `AWAITING_MANUAL_RESOLUTION`.
2. The workflow calls `Workflow.await { manualResolution != null }`. It is now parked and
   does no polling and runs no timer. It can wait for hours or days and survives worker
   restarts, because its state is rebuilt from the event history.
3. RCMP resolves the case: `POST /deviations/{deviationKey}/manual-resolution`.
4. ODM checks that the deviation is `AWAITING_MANUAL_RESOLUTION` (otherwise HTTP 409) and
   sends the **Temporal Signal** `manualResolution` to workflow `autorecovery-<deviationKey>`.
5. Temporal records `WorkflowExecutionSignaled` and runs the signal handler, which stores the
   payload. The `await` condition becomes true and the workflow resumes.
6. `closeRecovery` closes the alert and resolves the deviation as `MANUALLY_RESOLVED`.

## Drools rules (`autorecovery/autorecovery-rules.drl`, `autorecoveryKSession`)

These rules live in a separate kbase (`autorecoveryKBase`, added to `kmodule.xml`) and are
never evaluated together with `deviation-rules.drl`. The four rules are mutually exclusive,
and together they cover every possible fact:

| Rule | Condition | Decision |
|------|-----------|----------|
| Pre-release short pick with auto recovery enabled | `SHORT_PICK`, `PRE_RELEASE`, enabled | `AUTO_RECOVER` |
| Post-release deviation | `POST_RELEASE` | `MANUAL` |
| Pre-release deviation with auto recovery disabled | `PRE_RELEASE`, not enabled | `MANUAL` |
| Pre-release deviation with no automated recovery procedure | `PRE_RELEASE`, enabled, not `SHORT_PICK` | `MANUAL` |

The name of the rule that fired is stored in `recovery_deviations.decision_rule`.

**What stays in Kotlin:** signal invariants (`DeviationSignal.init`), status-transition rules
(`RecoveryStatus.canTransitionTo`), idempotency, and the workflow's control flow.

## Temporal

| Item | Value |
|------|-------|
| Workflow type | `AutorecoveryWorkflow` |
| Workflow ID | `autorecovery-<deviationKey>` |
| Task queue | `odm-autorecovery-queue` (worker hosted in odm-service) |
| Signal | `manualResolution` (`{resolvedBy, note}`) |
| Activities | `StartAutoRecovery`, `FindFulfillmentOption`, `RebookOrder`, `RequestManualResolution`, `CloseRecovery` |
| Result | `AUTO_RECOVERED` or `MANUALLY_RESOLVED` |

odm-service now needs Temporal at `TEMPORAL_ADDRESS` (default `127.0.0.1:7233`), the same
server order-service already uses.

## Idempotency

- `deviation_key` is the primary key of `recovery_deviations`. The row is inserted with
  `ON CONFLICT DO NOTHING`, so a duplicate signal never creates a second row and never runs
  Drools a second time. The response then has `"duplicate": true` and HTTP 200 instead of 202.
- The workflow ID is derived from `deviation_key`, and the start uses
  `WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE`, so Temporal itself refuses a second run.
- A duplicate signal never makes a new decision. It runs the same reconciliation as the
  background reconciler (see "Reliability" below): it starts a workflow that is missing, and
  performs a controlled restart of a run that ended without success.
- Every Activity is idempotent, because Temporal may retry it.

## Database (odm-postgres, Flyway `V2__autorecovery.sql` + `V3__autorecovery_reconciliation.sql`)

- `recovery_deviations`: the signal, `auto_recovery_enabled`, `decision`, `decision_rule`,
  `status` (`RECEIVED -> AUTO_RECOVERING | AWAITING_MANUAL_RESOLUTION -> RESOLVED`),
  `resolution`, `resolution_note` and `workflow_id`.
- `recovery_alerts`: one row per deviation, with `status` (`OPEN`/`CLOSED`), `owner`
  (`AUTORECOVERY`/`RCMP`), `reason`, `opened_at` and `closed_at`.

```bash
docker exec -it kotlin-ol-odm-postgres psql -U pocuser -d odmdb -c \
  "select deviation_key, type, release_stage, market, decision, status, resolution from recovery_deviations;"
docker exec -it kotlin-ol-odm-postgres psql -U pocuser -d odmdb -c \
  "select deviation_key, status, owner, reason, closed_at from recovery_alerts;"
```

The same data is available over REST at `GET /deviations/{deviationKey}/recovery`.

## Demo commands

See the README section "ODM autorecovery scenarios" for the copy-paste curl commands.

In Temporal UI (http://localhost:8080 -> Workflows), search for `autorecovery-<deviationKey>`.
- **Scenario 5:** status `Completed`, result `"AUTO_RECOVERED"`. The history shows
  `StartAutoRecovery`, `FindFulfillmentOption`, `RebookOrder` and `CloseRecovery`.
- **Scenario 6 before the signal:** status `Running`. The history ends after
  `RequestManualResolution`, with no timer and no further activity.
- **Scenario 6 after the signal:** a `WorkflowExecutionSignaled` event (`manualResolution`
  and its payload), then `CloseRecovery`, then `Completed` with result `"MANUALLY_RESOLVED"`.
  There is no `FindFulfillmentOption` or `RebookOrder` anywhere in the history.

## Reliability: reconciliation, controlled restart, POST_RELEASE invariant

### What Temporal reports, and what ODM does (verified against Temporal 1.31)

`TemporalAutorecoveryWorkflowAdapter.currentState` calls `WorkflowStub.describe()` for
`autorecovery-<deviationKey>`, which reports the **latest run**:

| Temporal answer | ODM state | Reconciler action |
|---|---|---|
| `WorkflowNotFoundException` | `NOT_FOUND` | Start the workflow with `startIfAbsent`, which keeps `REJECT_DUPLICATE`. |
| `RUNNING` | `RUNNING` | Nothing. |
| `COMPLETED` | `COMPLETED` | Never restart it. A warning is logged if the row is still unresolved. |
| `FAILED`, `TERMINATED`, `CANCELED` or `TIMED_OUT` | `ENDED_WITHOUT_SUCCESS` | Controlled restart with `ALLOW_DUPLICATE_FAILED_ONLY`, up to `max-workflow-restarts` times. |

The Temporal **server** enforces the reuse policies atomically. A new run with the same ID
can never start while a run is RUNNING. `REJECT_DUPLICATE` refuses to start after any closed
run. `ALLOW_DUPLICATE_FAILED_ONLY` refuses to start after a COMPLETED run. When two
reconcilers restart the same failed run concurrently, exactly one start succeeds.

### What a restarted run does
The reconciler reads the Temporal state first, then re-reads the row:
- **Row still `RECEIVED`:** nothing had been done yet, so the new run uses the original decision.
- **Row is `AUTO_RECOVERING` or `AWAITING_MANUAL_RESOLUTION`:** an earlier run had already
  started work, possibly a rebook. The new run is **MANUAL**: the alert is handed to RCMP, and
  the new run waits for the Signal. Automation is never repeated.

If the failed run had already received an RCMP resolution but failed while closing the alert,
RCMP resubmits `manual-resolution` to the new run.

### Configuration (`odm.autorecovery.reconciliation.*`)
`enabled` (default `false` when unset; `application.properties` turns it on via
`ODM_RECONCILIATION_ENABLED`), `interval-ms=30000`, `stale-after-seconds=30`,
`max-workflow-restarts=3`. Activity retries remain bounded at 5 attempts. Unit tests never
start a Spring context, so the scheduler never runs in them; they call the reconciler directly.
Each sweep checks every unresolved deviation older than the stale threshold with one
`describe` call. That's fine for a POC; a production version would page through the results.

### Other behaviour to know about
- **ODM crashes after the DB commit, before the workflow starts:** the row stays `RECEIVED`.
  The next sweep finds no workflow and starts it. If the same signal is sent again, that
  triggers the same repair immediately.
- **Restart limit reached:** an error is logged and the deviation is left for an operator.
- **POST_RELEASE is never auto-recovered,** enforced in three places on top of Drools:
  1. `RecoveryDeviation` cannot be constructed with `POST_RELEASE + AUTO_RECOVER`.
  2. `DeviationRecoveryService` overrides such a Drools verdict to MANUAL, logs an error, and
     records the override in `decision_rule`.
  3. The automation Activities refuse POST_RELEASE with a non-retryable failure. This also
     covers a workflow started by hand with crafted input.
- **Rebook idempotency (open point):** if a real Rebook API accepts a request but the response
  is lost, Temporal retries the Activity, and the same rebook is sent again. A real integration
  must pass an idempotency key, such as `deviationKey`. The mock doesn't need one.
- **Versioning:** long-waiting workflows would need `Workflow.getVersion()` before any future
  change to the order of Activity calls. This change adds no new calls. The new optional
  `manualReason` input field defaults to `null`, so payloads from existing runs still deserialize.
