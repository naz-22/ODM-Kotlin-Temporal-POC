# Solace in this POC

Solace PubSub+ is the asynchronous **business-event backbone**. It is intentionally *not*
on the critical path of the order lifecycle - Temporal owns that path end-to-end via Activities
that make direct REST calls. Solace exists for services that want to react to something that
happened, without order-service needing to know they exist.

## Publisher services and topics

| Publisher            | Topic                       | When                                             |
|-----------------------|------------------------------|---------------------------------------------------|
| inventory-service     | `order/inventory/reserved`   | Stock was successfully reserved for an order       |
| inventory-service     | `order/inventory/unavailable`| Stock could not be reserved                        |
| inventory-service     | `order/inventory/released`   | A reservation was released (compensation)          |
| payment-service       | `order/payment/succeeded`    | A payment attempt succeeded                        |
| payment-service       | `order/payment/failed`       | A payment attempt failed                           |
| order-service          | `order/confirmed`            | The order reached `ORDER_CONFIRMED`                |
| odm-service            | `order/deviation/resolved`   | An ODM recovery deviation reached `RESOLVED` (see [autorecovery.md](autorecovery.md#outgoing-deviationresolved-event)) |
| odm-service (Full Serve) | `odm/out/deviation/created`, `odm/out/deviation/alert/{consumer}/{orderId}`, `odm/out/deviation/closed`, `odm/out/deviation/lcm-closed` | Full-Serve deviation created, consumer alerts, alert closure and O360 closure. Persistent messages, broker-acknowledged (see [odm-fullserve-walkthrough.md](odm-fullserve-walkthrough.md)) |

Full-Serve ingestion uses **queues with client acknowledgement**, not direct subscriptions:
`ODM.DEVIATION.SIGNAL.Q` (subscribed to `odm/in/deviation/signal/>`) and
`ODM.DEVIATION.UPDATE.Q` (subscribed to `odm/in/deviation/update/>`), both consumed by odm-service.

`order/deviation/detected` from the original architecture sketch was folded into the two
`.../unavailable` and `.../failed` topics above - ODM subscribes directly to the events that
*are* the deviation trigger, rather than requiring every publisher to additionally publish a
second, more generic "deviation detected" event for the same fact. This keeps the topic list
small, per the brief's "keep topic strategy simple" instruction.

Only genuine business events are published - never "a row changed in the database" for its
own sake. For example, inventory-service does **not** publish anything when it merely reads
stock; it publishes when a reservation actually succeeds, fails, or is released.

## Consumer services

| Consumer                | Subscribes to                                              | What it does                                                                 |
|--------------------------|--------------------------------------------------------------|--------------------------------------------------------------------------------|
| notification-service     | `order/confirmed`                                             | Records an in-memory "notification processed" result for the order            |
| odm-service               | `order/inventory/unavailable`, `order/payment/failed`         | Idempotently records a `Deviation` row - **does not** evaluate Drools here    |
| odm-service (demo consumer) | `order/deviation/resolved`                                  | Demo only: logs each `DeviationResolved` event it receives, on its own Solace session |
| odm-service (Full Serve) | queues `ODM.DEVIATION.SIGNAL.Q`, `ODM.DEVIATION.UPDATE.Q` | Validates signals/updates and starts or signals `FullServeDeviationWorkflow` (client ack) |
| odm-service (Full-Serve demo consumer) | `odm/out/deviation/>` | Demo only: simulated SPM/Dynamics/O360 on its own session; `GET /fullserve/demo/received` |

## Why the main lifecycle is still Temporal orchestration

Solace has no request/response semantics and no durable "did this actually get handled"
guarantee that Temporal's workflow history gives you for free. If the order lifecycle were
driven by chaining events through Solace (`OrderCreated -> Solace -> Inventory -> Solace ->
Payment -> Solace -> Order`, as the brief explicitly warns against), you'd get **choreography**:
no single place to see or reason about the whole order's progress, and no clean way to express
"try payment up to 3 times, then compensate" without inventing ad-hoc correlation and timeout
logic that Temporal already gives you natively.

So the split is:

- **Temporal** = "what happens next in *this specific order's* lifecycle" (synchronous,
  ordered, retryable, has full history).
- **Solace** = "who else in the system cares that this happened" (asynchronous, fan-out,
  no reply expected).

## How asynchronous events and synchronous decisions coexist

This is the one place the two integration styles touch, and it's worth spelling out because
it's easy to get wrong: **ODM never makes Temporal wait for a Solace message.**

When inventory is unavailable, two things happen almost simultaneously:

1. **Synchronous path (authoritative):** the `getDeviationDecision` Activity in order-service
   calls `POST /deviations/decision` on odm-service directly. This call finds-or-creates a
   `Deviation` row keyed by `(orderId, type, attemptNumber)`, evaluates Drools, persists the
   decision, and returns it - all within the HTTP request/response. Temporal gets its answer
   immediately, with no dependency on Solace at all.
2. **Asynchronous path (observability/convergence):** inventory-service also published
   `InventoryUnavailable` to Solace. odm-service's `SolaceDeviationEventListener` consumes it
   whenever it happens to arrive - before, after, or concurrently with path 1 - and
   idempotently records the same `Deviation` row using the *same* correlation key. It never
   evaluates Drools or overwrites a decision that already exists.

Because both paths write to the same key with an idempotent "insert if absent" semantic,
the row is created exactly once. If the Solace path created it first, the decision endpoint writes
its decision into that same row; a Solace event arriving later is a no-op. There is no race that can produce
two different decisions, and no reason to ever make the decision endpoint block on message
consumption.

## Local broker

`docker-compose.yml` runs `solace/solace-pubsub-standard` (the free, non-HA "standard" edition
meant for local development) with:

- `55555` - SMF, what every service's `solace.java.host` points at
- `8087` - web management console (remapped from the image's default `8080` so it never
  collides with Temporal UI, which also defaults to `8080`)
- `9000` - REST messaging; the Full-Serve demo uses it to stand in for OE/OEP publishing
- `8008` - Web Messaging (used by the broker's "Try Me!" page)
- `5550` - health check

All services connect to the broker's default message-VPN (`default`) with the `default`
client username and an empty password (the defaults in each `application.properties`) - fine for local dev, not something to carry into
a real deployment. Services run on the host use `tcp://localhost:55555` (the default); the
containerised services (`docker compose --profile apps`) get `SOLACE_HOST=tcp://solace:55555`.

To use an external or shared broker instead, set `SOLACE_HOST`, `SOLACE_VPN`,
`SOLACE_CLIENT_USERNAME` and `SOLACE_CLIENT_PASSWORD` for every service, and for odm-service
also `ODM_FULLSERVE_PROVISION_QUEUES=false`, `ODM_FULLSERVE_DEMO_CONSUMER_ENABLED=false` and
`ODM_DEMO_RESOLVED_CONSUMER_ENABLED=false`.
The two Full-Serve queues and their topic subscriptions must then be provisioned on that
broker (see [odm-fullserve-walkthrough.md](odm-fullserve-walkthrough.md#configuration)).
