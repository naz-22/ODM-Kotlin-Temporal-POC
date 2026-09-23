# Architecture

## Services and responsibilities

| Service               | Port | Owns                                                                 |
|------------------------|------|------------------------------------------------------------------------|
| order-service           | 8081 | Order domain, lifecycle, persistence, starting/orchestrating the Temporal workflow |
| inventory-service       | 8082 | Product stock, reservations                                            |
| payment-service         | 8083 | Payment attempts, deterministic demo payment outcomes                  |
| notification-service    | 8084 | Consuming `order/confirmed` and producing a notification result        |
| odm-service              | 8085 | Deviations, the Drools-backed decision API                             |

Each service owns its own PostgreSQL database (order/inventory/payment/odm - notification-service
has none) and is only ever reached by another service through REST or Solace - never through a
shared database or direct code dependency. This is the hexagonal architecture applied at the
system level, not just inside one service: `domain` → `application/ports` → `adapters`, repeated
per service, with the adapters being the only place that knows about Postgres, Temporal, REST
clients, or Solace.

## Temporal: the durable orchestrator

`OrderLifecycleWorkflowImpl` (order-service) is the **only** place that decides what happens
next in an order's lifecycle. It is deliberately simple and deterministic:

```
validateOrder
    -> reserveInventory
        -> [UNAVAILABLE] -> getDeviationDecision(INVENTORY_UNAVAILABLE) -> failOrder
        -> [RESERVED]    -> processPayment(attempt 1)
                                -> [SUCCEEDED] -> confirmOrder -> completeOrder
                                -> [FAILED]    -> getDeviationDecision(PAYMENT_FAILED, attempt)
                                                     -> RETRY      -> processPayment(attempt + 1) [loop]
                                                     -> COMPENSATE -> releaseInventory -> cancelOrder
                                                     -> FAIL_ORDER -> releaseInventory -> failOrder
```

The Workflow class touches **nothing** except Activity stubs and plain control flow - no JPA,
no HTTP, no Spring beans, no Solace. That work lives in `OrderLifecycleActivitiesImpl`, which is
where the Workflow's abstract steps become real: DB transitions (via the application layer),
outbound REST calls (via output ports/REST clients), and the one Solace publish order-service
does (`OrderConfirmed`).

### Two different kinds of "retry" - do not conflate them

- **Technical retry (Temporal's `RetryOptions`)**: fires automatically when an Activity
  *throws* - a network blip, the downstream service being briefly unreachable, a 5xx. This is
  configured once, on the shared `ActivityOptions` in `OrderLifecycleWorkflowImpl`, and is
  invisible to the business logic.
- **Business retry (ODM/Drools)**: a payment attempt that comes back as a normal, successful
  HTTP response saying "declined" is not a technical failure - it is a legitimate business
  outcome. Whether to try again is a *business* decision, made once by Drools via
  `getDeviationDecision`, and the Workflow just obeys whatever comes back
  (`RETRY`/`COMPENSATE`/`FAIL_ORDER`). See [drools.md](drools.md).

An Activity method never throws to represent "payment declined" or "out of stock" - those are
returned as normal values (`PaymentAttemptResult`, `InventoryReservationResult`). Throwing is
reserved for genuine technical failures Temporal should retry.

## The order lifecycle and why there's no `PUT /orders/{id}/status`

```
ORDER_PLACED -> VALIDATING -> INVENTORY_RESERVATION_PENDING -> INVENTORY_RESERVED
             -> PAYMENT_PENDING -> PAYMENT_CONFIRMED -> ORDER_CONFIRMED
             -> NOTIFICATION_PENDING -> COMPLETED

(failure states: ORDER_FAILED, ORDER_CANCELLED)
```

`OrderStatus.canTransitionTo()` (order-service, pure Kotlin, zero framework dependencies) is
the single source of truth for which transition is legal. `order-service` exposes only
`POST /orders` and `GET /orders/{id}` - there is intentionally no endpoint that lets a caller
set an order's status directly. Every transition is a *consequence* of the Temporal workflow
calling an Activity, which calls back into `OrderApplicationService.transitionOrder()`, which
enforces `canTransitionTo()` before writing anything.

## Business-event integration (Solace)

See [solace.md](solace.md) for topics, publishers, and consumers. In short: inventory-service
and payment-service publish what happened to them; notification-service and odm-service react
to it. Order-service's workflow never depends on any of that being consumed before it can move
forward.

## ODM and Drools

See [drools.md](drools.md) for the full explanation. In short: `odm-service` exposes one
synchronous, idempotent decision endpoint that Temporal calls directly - it does not, and must
not, depend on a Solace event having been consumed first (see "How asynchronous events and
synchronous decisions coexist" in solace.md for how the two paths converge safely).

## Happy path vs. failure paths

**Happy path** - `customer-1` / `PROD-1`:
`ORDER_PLACED -> VALIDATING -> INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> ORDER_CONFIRMED ->
COMPLETED`

**Inventory failure** - `PROD-OUT-OF-STOCK`:
`InventoryUnavailable -> Solace (observed) & ODM decision endpoint (authoritative) -> Drools:
FAIL_ORDER -> Temporal -> ORDER_FAILED`

**Payment business retry** - `cust-payment-fail-once`:
`Payment attempt 1 fails -> ODM/Drools: RETRY -> Temporal -> Payment attempt 2 succeeds ->
COMPLETED`

**Payment compensation** - `cust-payment-fail-always`:
`Payment fails on every attempt -> ODM/Drools: RETRY until attempts exhausted, then COMPENSATE
-> Temporal -> ReleaseInventory -> ORDER_CANCELLED`

See the [README](../README.md) for exact `curl` commands for each scenario.

## ODM autorecovery (Scenarios 5 and 6)

This is an additive ODM capability that sits alongside everything above and changes none of
it. An upstream deviation signal (`POST /deviations/signal`) is ingested by odm-service. ODM
applies its own `(deviationType + market)` auto-recovery policy and a separate Drools
ksession, then starts its **own** Temporal workflow, `AutorecoveryWorkflow`. That workflow runs
on the `odm-autorecovery-queue` task queue with a worker hosted in odm-service, never on
order-service's `order-lifecycle-task-queue`.

Two paths:
- **PRE_RELEASE `SHORT_PICK`:** automated recovery (Fulfillment Options, then Rebook).
- **POST_RELEASE, or no automated path:** an alert for RCMP, and a durable
  `Workflow.await` until a manual-resolution Temporal Signal arrives.

The state lives in two new tables, `recovery_deviations` and `recovery_alerts`. Solace is **not**
involved in the Temporal orchestration: Temporal alone drives the recovery. Once a deviation
reaches `RESOLVED`, ODM publishes a `DeviationResolved` event to the Solace topic
`order/deviation/resolved`. That event is an asynchronous notification for downstream consumers,
not a step in the recovery. See [autorecovery.md](autorecovery.md).
