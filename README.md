# Kotlin Order Lifecycle - Temporal + Solace + ODM/Drools POC

A small, demo-friendly architecture reference showing Temporal (durable orchestration),
Solace PubSub+ (business-event backbone), and an Order Deviation Management service backed by
Drools (business retry/compensation/failure decisions), wired together with Kotlin + Spring
Boot in a hexagonal architecture. See [docs/architecture.md](docs/architecture.md),
[docs/drools.md](docs/drools.md), and [docs/solace.md](docs/solace.md) for the full picture.

> **Verification status of this delivery:** the code was written and reviewed carefully by
> hand, but could not be compiled, tested, or run in the environment that produced it -
> there was no `mvn` binary, no cached Maven dependencies, no network access to Maven Central,
> and no Docker available there. Every "did this actually run" claim below is marked
> **UNVERIFIED** rather than asserted. See the bottom of this file for exactly what that means
> and what to check first.

## Prerequisites

- Java 21 (the project targets `java.version=21`)
- Maven (a wrapper - `./mvnw` - is included in every service, so a local Maven install isn't
  strictly required, but network access to Maven Central is)
- Docker and Docker Compose, for the infrastructure (5x PostgreSQL, Temporal, Temporal UI,
  Solace PubSub+)

## Services and ports

| Service               | Port |
|------------------------|------|
| order-service           | 8081 |
| inventory-service       | 8082 |
| payment-service         | 8083 |
| notification-service    | 8084 |
| odm-service              | 8085 |
| Temporal UI              | 8080 |
| Temporal gRPC             | 7233 |
| Solace web console (remapped) | 8087 |
| Solace SMF                | 55555 |

| Database              | Port |
|------------------------|------|
| order-postgres           | 5434 |
| Temporal's own postgres  | 5435 |
| inventory-postgres       | 5436 |
| payment-postgres         | 5437 |
| odm-postgres              | 5438 |

## 1. Start infrastructure

```bash
cp .env.example .env   # adjust credentials if you want; the defaults work as-is
docker compose up -d
```

This brings up all five PostgreSQL containers, Temporal (+ its own Postgres + namespace
creation), Temporal UI, and the Solace PubSub+ broker. Give it a minute or two the first time -
Temporal's schema setup and namespace creation run as short-lived containers before the main
`temporal` service becomes healthy.

Check everything is up:

```bash
docker compose ps
```

- Temporal UI: http://localhost:8080
- Solace web console: http://localhost:8087 (login `admin` / `admin`)

## 2. Build

The repository root is a Maven multi-module aggregator (`pom.xml`) listing all five services.
Build and test everything in one reactor run from the repository root:

```bash
./mvnw clean test
# or, with a system-installed Maven:
mvn clean test
```

The five services still have no `<dependency>` relationships on each other in Maven - they are
independently deployable services that only ever talk to each other over REST or Solace at
runtime - so each one also remains independently buildable from its own directory if you only
want to iterate on one:

```bash
(cd order-service && ./mvnw clean test)
```

## 3. Run the services

Each service reads its datasource/Solace/downstream-URL configuration from environment
variables with `localhost`-friendly defaults (see each service's
`src/main/resources/application.properties`), so running them locally against the
docker-compose infrastructure needs no extra flags:

```bash
(cd order-service        && ./mvnw spring-boot:run) &
(cd inventory-service     && ./mvnw spring-boot:run) &
(cd payment-service       && ./mvnw spring-boot:run) &
(cd notification-service  && ./mvnw spring-boot:run) &
(cd odm-service            && ./mvnw spring-boot:run) &
```

Start `odm-service`, `inventory-service`, and `payment-service` before `order-service` places
its first order - order-service's Temporal Activities call out to all three.

## 4. Demo scenarios

All four scenarios below hit `order-service` (`POST /orders`) and are observable in real time in
the Temporal UI (http://localhost:8080 -> Workflows) by workflow ID `order-<orderId>`.

### Scenario 1 - Happy path

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","productId":"PROD-1","quantity":1,"amount":100.00}'
```

Expected: the returned order starts at `ORDER_PLACED`; polling
`GET http://localhost:8081/orders/{id}` shows it progress through `VALIDATING ->
INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> ORDER_CONFIRMED -> COMPLETED`. Check
`GET http://localhost:8084/notifications/{id}` afterwards to see notification-service's Solace
consumer picked up `order/confirmed`.

### Scenario 2 - Inventory failure

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","productId":"PROD-OUT-OF-STOCK","quantity":1,"amount":100.00}'
```

Expected: `ORDER_FAILED`. `GET http://localhost:8085/deviations/order/{id}` shows an
`INVENTORY_UNAVAILABLE` deviation with `decision: FAIL_ORDER`.

### Scenario 3 - Payment business retry

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-payment-fail-once","productId":"PROD-1","quantity":1,"amount":100.00}'
```

Expected: `COMPLETED`, after one internal business retry. The Temporal UI's workflow history
shows two `processPayment` Activity calls; `GET http://localhost:8085/deviations/order/{id}`
shows a `PAYMENT_FAILED` deviation with `decision: RETRY`.

### Scenario 4 - Payment compensation

```bash
curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-payment-fail-always","productId":"PROD-1","quantity":1,"amount":100.00}'
```

Expected: `ORDER_CANCELLED`, after `payment.max-attempts` (default 3) failed attempts. The
workflow history shows `releaseInventory` followed by `cancelOrder`;
`GET http://localhost:8082/inventory/reservations/{id}/release` having already run means the
stock is back to its original count.

## 5. ODM autorecovery scenarios

Scenarios 5 and 6 go straight to `odm-service`, not through `POST /orders`. Each one simulates
a deviation signal from an upstream fulfilment system. ODM applies its auto-recovery policy and
the Drools rules, then starts its own Temporal workflow, `AutorecoveryWorkflow`, with ID
`autorecovery-<deviationKey>` on task queue `odm-autorecovery-queue`. It never uses
`order-lifecycle-task-queue`. These scenarios need only `odm-service` plus the infrastructure
(Temporal, odm-postgres, Solace). See [docs/autorecovery.md](docs/autorecovery.md) for the full
explanation.

### Scenario 5 - Short Pick Auto-Recovery (PRE_RELEASE)

The warehouse picked 3 of 5 units before the order was released. `SHORT_PICK` has
auto-recovery enabled for `US` (`odm.autorecovery.enabled-markets[SHORT_PICK]=US,IN`), so
Drools decides `AUTO_RECOVER`. Temporal then finds an alternative through the mock
Fulfillment Options API, rebooks through the mock Rebook API, and closes the alert.

```bash
curl -s -X POST http://localhost:8085/deviations/signal \
  -H 'Content-Type: application/json' \
  -d '{"deviationKey":"SP-1001","orderId":"order-1001","type":"SHORT_PICK","releaseStage":"PRE_RELEASE",
       "market":"US","itemId":"ITEM-1","requestedQuantity":5,"pickedQuantity":3,"reason":"Only 3 of 5 units picked"}'

curl -s http://localhost:8085/deviations/SP-1001/recovery
```

Expected: HTTP 202 with `"decision":"AUTO_RECOVER"`, then `"status":"RESOLVED"`,
`"resolution":"AUTO_RECOVERED"` and `"alert":{"status":"CLOSED","owner":"AUTORECOVERY",...}`.
In Temporal UI, workflow `autorecovery-SP-1001` is Completed with result `"AUTO_RECOVERED"`.

Variants that fall back to manual handling:
- `"itemId":"ITEM-NO-ALTERNATIVE"`: no fulfilment option is found.
- `"itemId":"ITEM-REBOOK-FAIL"`: the rebook fails.
- `"market":"GB"`: auto-recovery is disabled for that market, so Drools decides `MANUAL`.

In each case the alert goes to `RCMP` and the workflow waits, as in Scenario 6. Use a new
`deviationKey` for each attempt, because sending the same key again is treated as a duplicate
and returns HTTP 200 with `"duplicate":true`.

### Scenario 6 - Provided Service Failure / Manual Resolution (POST_RELEASE)

The last-mile service failed after release. Drools decides `MANUAL`, so there is no
Fulfillment Options or Rebook call. The workflow opens an alert for RCMP and parks in
`Workflow.await`, until RCMP's resolution arrives as a Temporal Signal.

```bash
curl -s -X POST http://localhost:8085/deviations/signal \
  -H 'Content-Type: application/json' \
  -d '{"deviationKey":"PSF-2001","orderId":"order-2001","type":"PROVIDED_SERVICE_FAILURE",
       "releaseStage":"POST_RELEASE","market":"US","reason":"Last-mile carrier could not deliver"}'

curl -s http://localhost:8085/deviations/PSF-2001/recovery
# -> "status":"AWAITING_MANUAL_RESOLUTION", "alert":{"status":"OPEN","owner":"RCMP",...}
# Temporal UI: autorecovery-PSF-2001 is Running, waiting.

curl -s -X POST http://localhost:8085/deviations/PSF-2001/manual-resolution \
  -H 'Content-Type: application/json' \
  -d '{"resolvedBy":"rcmp-agent-7","note":"Redelivery booked with customer"}'

curl -s http://localhost:8085/deviations/PSF-2001/recovery
# -> "status":"RESOLVED", "resolution":"MANUALLY_RESOLVED", "alert":{"status":"CLOSED",...}
```

In Temporal UI, the history shows `WorkflowExecutionSignaled` (`manualResolution`) followed by
`CloseRecovery`, and the result is `"MANUALLY_RESOLVED"`. If you call manual resolution while a
deviation is not waiting for it, ODM returns HTTP 409.

### DeviationResolved event (Scenarios 5 and 6)

When a deviation reaches `RESOLVED`, whether auto-recovered or manually resolved, ODM
publishes a `DeviationResolved` event to the Solace topic `order/deviation/resolved`. A
demo-only downstream consumer logs each event it receives. Look for
`Received DeviationResolved event: deviationKey=…, resolution=…` in the odm-service log. See
[docs/autorecovery.md](docs/autorecovery.md#outgoing-deviationresolved-event).

## Troubleshooting

- **Temporal namespace/schema containers keep restarting**: they wait on `temporal-postgresql`
  and retry with backoff - give it up to a couple of minutes on first startup.
- **A service can't reach Postgres**: check `.env` matches what `docker compose ps` shows, and
  that the service's own `application.properties` port/host env vars
  (`ORDER_DB_HOST`/`ORDER_DB_PORT`, etc.) aren't overridden somewhere unexpected.
- **A service can't reach Solace**: confirm `docker compose ps` shows `solace` as healthy (its
  first boot can take up to a minute) and that `solace.java.host` resolves - when running
  services outside Docker against dockerized infra, `tcp://localhost:55555` is correct; if you
  containerize the services themselves later, that needs to become `tcp://solace:55555`.
- **`mvnw` fails to download the Maven distribution or dependencies**: you need network access
  to Maven Central (`repo.maven.apache.org`) - this could not be verified in the environment
  this POC was assembled in (see below).

## On verification (please read before assuming anything below "just works")

This project was assembled and reviewed by hand in a sandboxed environment with:

- Java 21 present, but **no `mvn` binary and no populated `~/.m2` cache**
- **No network route to Maven Central** (`repo.maven.apache.org` was unreachable)
- **No Docker daemon**

That means **no `mvn clean test`, no `docker compose config`/`up`, and none of the four demo
scenarios were actually executed.** Every version number (Spring Boot 4.1.1, Kotlin 2.3.21,
Temporal SDK 1.31.0, Drools 9.44.0.Final, solace-spring-boot-starter 3.0.0) is consistent with
what the original project already used or a plausible current release, but **the Drools and
Solace-starter version numbers in particular could not be checked against what's actually
published**. Before relying on this for a real demo:

1. Run `docker compose config` to validate the compose file's YAML/interpolation.
2. Run `./mvnw clean test` (or `mvn clean test`) from the repository root and fix any
   dependency-resolution or compilation errors Maven Central turns up that this review could
   not catch by reading alone. In particular, the exact version numbers for
   `com.solace.spring.boot:solace-spring-boot-starter` and `org.drools:*` could not be checked
   against what's actually published - see `odm-service/pom.xml` and the root `pom.xml`.
3. Run `docker compose up -d` and confirm all containers report healthy.
4. Walk through the four scenarios above.

Everything above the "Prerequisites" section describes the intended, reviewed design - not a
confirmed-working system.
