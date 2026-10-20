# Kotlin Order Lifecycle - Temporal + Solace + ODM/Drools POC

Proof of concept for moving ODM (Order Deviation Management) orchestration to Temporal. Five
Kotlin/Spring Boot services in a hexagonal layout, with Temporal for durable orchestration,
Solace PubSub+ for business events, Drools for ODM business decisions and one PostgreSQL
database per service.

The repository contains **three independent functional flows**. All three are part of the POC.

| Flow | Entry point | Temporal workflow / task queue | Services needed |
|---|---|---|---|
| **1. Order Lifecycle** (Scenarios 1-4) | `POST /orders` on order-service | `OrderLifecycleWorkflow`, ID `order-<orderId>`, `order-lifecycle-task-queue` | all five |
| **2. Auto-Recovery** (Scenarios 5-6) | `POST /deviations/signal` on odm-service | `AutorecoveryWorkflow`, ID `autorecovery-<deviationKey>`, `odm-autorecovery-queue` | odm-service |
| **3. Full-Serve Deviation Lifecycle** | Solace queue `ODM.DEVIATION.SIGNAL.Q` (or `POST /fullserve/deviations/signals`) | `FullServeDeviationWorkflow`, ID `fullserve-deviation-<orderId>`, `odm-fullserve-queue` | odm-service |

The flows share infrastructure but no workflow, task queue, table or Drools kbase. Full Serve
never invokes Auto-Recovery (Full-Serve deviations are not eligible for it).

Further reading - one document per topic:

- [docs/architecture.md](docs/architecture.md) - services, Order Lifecycle orchestration, how the three flows fit together
- [docs/autorecovery.md](docs/autorecovery.md) - Auto-Recovery design, reliability (reconciler), DeviationResolved event
- [docs/odm-fullserve-walkthrough.md](docs/odm-fullserve-walkthrough.md) - Full Serve flow-to-code walkthrough, retries, assumptions, known gaps
- [docs/drools.md](docs/drools.md) - the three rule sets and what stays in Kotlin
- [docs/solace.md](docs/solace.md) - topics, queues, publishers, consumers, external broker settings

## Technology and prerequisites

| | Version |
|---|---|
| Java | 21 (needed only to build/run on the host) |
| Kotlin / Spring Boot | 2.3.21 / 4.1.1 |
| Temporal SDK / server / UI | 1.31.0 / 1.31.0 / 2.49.1 |
| Drools | 9.44.0.Final |
| Solace broker / Spring starter | PubSub+ Standard 10.26.0.8894 / 6.0.1 |
| PostgreSQL | 16 (schemas managed by Flyway) |
| Build | Maven via the wrapper `./mvnw` (Maven 3.9.16 is downloaded on first use) |
| Docker | Docker Engine with Compose v2 (profiles, `depends_on` conditions and `--wait`) |

To run everything in Docker you need only Git and Docker; Java 21 is needed only for Option B
(services on the host) and for running the tests. Network access to Docker Hub and Maven Central
is needed for the first build.

**Free host ports:** 7233, 8080-8085, 8087, 8008, 9000, 55555, 5550 and 5434-5438 (see the
table below). If one is taken, stop whatever uses it first; the ports are not configurable
through `.env`.

**Tested platform:** Linux x86-64 (Docker 29, Compose v5). The images used are published for
amd64 and arm64, but macOS, Windows and ARM machines have not been tried, and CPU/memory needs
were not measured.

## Services and ports

| Service | Port | | Infrastructure | Host port |
|---|---|---|---|---|
| order-service | 8081 | | Temporal gRPC | 7233 |
| inventory-service | 8082 | | Temporal UI | 8080 |
| payment-service | 8083 | | Solace SMF | 55555 |
| notification-service | 8084 | | Solace web console (`admin`/`admin`) | 8087 |
| odm-service | 8085 | | Solace REST messaging | 9000 |
| | | | Solace Web Messaging (Try Me!) | 8008 |
| | | | order / inventory / payment / odm Postgres | 5434 / 5436 / 5437 / 5438 |
| | | | Temporal's own Postgres | 5435 |

## Configuration

Copy the example file once; docker compose reads `.env` automatically:

```bash
cp .env.example .env
```

`.env` holds the shared local database credentials and the four database names, plus optional
demo settings (commented out). `.env` is gitignored; the values in `.env.example` are local
throwaway values only.

Each service reads its settings from environment variables with host-friendly defaults (see
each `src/main/resources/application.properties`):

| Variable | Used by | Default (host run) |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | all with a DB | none - must be set (from `.env`) |
| `ORDER_DB_HOST`/`_PORT`, `INVENTORY_DB_HOST`/`_PORT`, `PAYMENT_DB_HOST`/`_PORT`, `ODM_DB_HOST`/`_PORT` | each service | `localhost` and its host port above |
| `POSTGRES_DB` | each service | `orderdb` / `inventorydb` / `paymentdb` / `odmdb` |
| `TEMPORAL_ADDRESS` | order-service, odm-service | `127.0.0.1:7233` |
| `SOLACE_HOST`, `SOLACE_VPN`, `SOLACE_CLIENT_USERNAME`, `SOLACE_CLIENT_PASSWORD` | all | `tcp://localhost:55555`, `default`, `default`, empty |
| `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `ODM_SERVICE_URL` | order-service | `http://localhost:8082` / `8083` / `8085` |
| `TEMPORAL_DEMO_DELAY_ENABLED`, `TEMPORAL_DEMO_DELAY_SECONDS` | order-service | `true`, `10` (demo pause after ODM decisions) |
| `ODM_RECONCILIATION_ENABLED` | odm-service (Auto-Recovery) | `true` |
| `ODM_DEMO_RESOLVED_CONSUMER_ENABLED` | odm-service (Auto-Recovery demo consumer) | `true` |
| `ODM_FULLSERVE_ALERT_SCHEDULE`, `ODM_FULLSERVE_ALERT_MARKETS` | odm-service (Full Serve) | `0s,2m,10m`, `US,GB,DE` |
| `ODM_FULLSERVE_LISTENER_ENABLED`, `ODM_FULLSERVE_PROVISION_QUEUES`, `ODM_FULLSERVE_DEMO_CONSUMER_ENABLED` | odm-service (Full Serve) | `true` (provisioning: local broker only) |
| `ODM_FULLSERVE_SIGNAL_QUEUE`, `ODM_FULLSERVE_UPDATE_QUEUE` | odm-service (Full Serve) | `ODM.DEVIATION.SIGNAL.Q`, `ODM.DEVIATION.UPDATE.Q` |
| `SOLACE_ADMIN_PASSWORD` | local Solace container (compose only) | `admin` |

`docker-compose.yml` sets the container-network values (`odm-postgres:5432`, `temporal:7233`,
`tcp://solace:55555`, `http://odm-service:8085`, ...) for the containerised services itself.

## Running locally

### Quick start (everything in Docker)

```bash
git clone https://github.com/naz-22/ODM-Kotlin-Temporal-POC.git
cd ODM-Kotlin-Temporal-POC
git checkout feature/full-serve-deviation-lifecycle-poc   # main does not contain the Docker setup
cp .env.example .env
docker compose --profile apps up -d --build --wait
```

Then follow [Flow 1](#flow-1---order-lifecycle-scenarios-1-4),
[Flow 2](#flow-2---auto-recovery-scenarios-5-6) and
[Flow 3](#flow-3---full-serve-deviation-lifecycle) below. All three flows run in the same stack.

There are two supported ways to run the POC. Both use the same `docker-compose.yml`.

### Option A - everything in Docker (verified)

```bash
cp .env.example .env                                     # once; compose needs it (DB names and credentials)
docker compose --profile apps up -d --build --wait       # infrastructure + all five services
docker compose --profile apps ps                         # current state
```

`--wait` returns only when every container with a health check is healthy (exit code 0), or
fails if one does not become healthy. Two things in `ps` are expected and not errors:
`temporal-ui` has no health check and only shows `Up`; `temporal-admin-tools` and
`temporal-create-namespace` are one-off setup containers that exit with code 0 once the Temporal
schema and namespace exist (`docker compose --profile apps ps -a` shows them as `Exited (0)`).

The first build takes several minutes (Maven dependencies are downloaded in their own image
layer, built from the POMs only, so a source change does not download them again). The `apps` profile adds the five services to the infrastructure;
start order is enforced with health checks (`depends_on: condition: service_healthy` /
`service_completed_successfully`), not just container start.

Start only what one flow needs, for example Auto-Recovery or Full Serve:

```bash
docker compose --profile apps up -d --build odm-service   # pulls in odm-postgres, Temporal, Solace
```

To shorten the Full-Serve alert schedule for a demo, set `ODM_FULLSERVE_ALERT_SCHEDULE=0s,1m,3m`
in `.env` (or the shell) before `up`.

### Option B - infrastructure in Docker, services on the host (not freshly verified)

Useful while developing (IDE debugging, fast restarts). These commands match the code and
configuration, but this way of running was not re-verified after the Docker changes (see
[Verification status](#verification-status)).

```bash
cp .env.example .env
docker compose up -d                 # infrastructure only (no profile)
set -a; . ./.env; set +a             # export DB_USERNAME / DB_PASSWORD for the services
(cd odm-service          && ./mvnw spring-boot:run) &
(cd inventory-service    && ./mvnw spring-boot:run) &
(cd payment-service      && ./mvnw spring-boot:run) &
(cd notification-service && ./mvnw spring-boot:run) &
(cd order-service        && ./mvnw spring-boot:run) &
```

Start odm-, inventory- and payment-service before order-service places its first order. Do
not mix the options for the same service: both bind the same host port and poll the same
Temporal task queue. Stop the host services with Ctrl+C / `kill`, and the infrastructure with
`docker compose stop` (no profile is needed when only the infrastructure is running).

### Logs, stopping and data

When the services run in Docker (Option A), include `--profile apps` in every command below.
Without it, Compose only acts on the infrastructure services and leaves the five application
containers alone.

```bash
docker compose --profile apps logs -f odm-service    # one service
docker compose --profile apps stop                   # stop, keep containers and data
docker compose --profile apps start                  # start again
docker compose --profile apps down                   # remove containers, KEEP named volumes
docker compose --profile apps down -v                # also DELETE all data (DBs, Temporal history, Solace queues)
```

Databases, Temporal's database and the Solace broker (including the Full-Serve queues) use
named volumes, so `down`/`up` keeps deviations, orders and running workflows. Only `down -v`
deletes them.

Temporal UI: http://localhost:8080 (Workflows -> search by workflow ID). Solace console:
http://localhost:8087.

## Flow 1 - Order Lifecycle (Scenarios 1-4)

`POST /orders` persists the order and starts `OrderLifecycleWorkflow`. Its Activities call
inventory-service and payment-service over REST and ask odm-service (`POST /deviations/decision`,
Drools `deviationKSession`) what to do after a failure. inventory/payment publish business
events to Solace; notification-service consumes `order/confirmed`. Main code:
`order-service/.../adapters/input/temporal/OrderLifecycleWorkflowImpl.kt`,
`.../adapters/output/temporal/OrderLifecycleActivitiesImpl.kt`, `domain/model/Order.kt`
(allowed status transitions). Details: [docs/architecture.md](docs/architecture.md).

Needs all five services. The demo customers and products are fixed in
`PaymentDecisionRules` and the inventory seed data
(`inventory-service/src/main/resources/db/migration/V1__init.sql`).

```bash
# Scenario 1 - happy path -> COMPLETED
curl -s -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","productId":"PROD-1","quantity":1,"amount":100.00}'
# Scenario 2 - inventory unavailable -> ORDER_FAILED (ODM decision FAIL_ORDER)
curl -s -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","productId":"PROD-OUT-OF-STOCK","quantity":1,"amount":100.00}'
# Scenario 3 - payment fails once, ODM decides RETRY -> COMPLETED
curl -s -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-payment-fail-once","productId":"PROD-1","quantity":1,"amount":100.00}'
# Scenario 4 - payment always fails, RETRY until payment.max-attempts (3), then COMPENSATE -> ORDER_CANCELLED
curl -s -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-payment-fail-always","productId":"PROD-1","quantity":1,"amount":100.00}'
```

Each call returns the order with `"status":"ORDER_PLACED"` and its `id`. To keep the ID for the
follow-up requests, capture it (works for any scenario; `python3` is used only to read the JSON):

```bash
ID=$(curl -s -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"customer-1","productId":"PROD-1","quantity":1,"amount":100.00}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "$ID"
```

Then verify:

```bash
curl -s http://localhost:8081/orders/$ID                # final status
curl -s http://localhost:8085/deviations/order/$ID      # Scenarios 2-4: ODM deviation rows and decisions
curl -s http://localhost:8084/notifications/$ID         # Scenarios 1 and 3: notification-service consumed order/confirmed
```

In Temporal UI, workflow `order-$ID` shows every Activity; Scenario 3 has two `ProcessPayment`
calls, Scenario 4 ends with `ReleaseInventory` and `CancelOrder`. With the default demo delay,
each ODM decision is followed by a 10 s durable timer, so Scenario 4 takes about 30 s.

## Flow 2 - Auto-Recovery (Scenarios 5-6)

`POST /deviations/signal` simulates an upstream deviation signal. ODM applies its
`(type, market)` policy and the Drools `autorecoveryKSession`, stores a `recovery_deviations`
row and starts `AutorecoveryWorkflow`. Fulfillment Options and Rebook are **mocks**
(`MockFulfillmentOptionsClient`, `MockRebookClient`). Needs only odm-service and its
infrastructure. Details: [docs/autorecovery.md](docs/autorecovery.md).

```bash
# Scenario 5 - PRE_RELEASE short pick in an enabled market -> AUTO_RECOVER
curl -s -X POST http://localhost:8085/deviations/signal -H 'Content-Type: application/json' \
  -d '{"deviationKey":"SP-1001","orderId":"order-1001","type":"SHORT_PICK","releaseStage":"PRE_RELEASE",
       "market":"US","itemId":"ITEM-1","requestedQuantity":5,"pickedQuantity":3,"reason":"Only 3 of 5 units picked"}'
curl -s http://localhost:8085/deviations/SP-1001/recovery
# POST -> 202 "decision":"AUTO_RECOVER"; GET then shows "status":"RESOLVED","resolution":"AUTO_RECOVERED", alert CLOSED

# Scenario 6 - POST_RELEASE provided service failure -> MANUAL, waits for RCMP
curl -s -X POST http://localhost:8085/deviations/signal -H 'Content-Type: application/json' \
  -d '{"deviationKey":"PSF-2001","orderId":"order-2001","type":"PROVIDED_SERVICE_FAILURE",
       "releaseStage":"POST_RELEASE","market":"US","reason":"Last-mile carrier could not deliver"}'
curl -s http://localhost:8085/deviations/PSF-2001/recovery      # AWAITING_MANUAL_RESOLUTION, alert OPEN owner RCMP
curl -s -X POST http://localhost:8085/deviations/PSF-2001/manual-resolution -H 'Content-Type: application/json' \
  -d '{"resolvedBy":"rcmp-agent-7","note":"Redelivery booked with customer"}'
curl -s http://localhost:8085/deviations/PSF-2001/recovery      # RESOLVED / MANUALLY_RESOLVED, alert CLOSED
```

Fallback variants of Scenario 5 (each ends waiting for RCMP like Scenario 6): `"itemId":"ITEM-NO-ALTERNATIVE"`
(no fulfilment option), `"itemId":"ITEM-REBOOK-FAIL"` (rebook fails), `"market":"GB"` (auto-recovery
disabled). Use a new `deviationKey` each time: a repeated key returns `200` with `"duplicate":true`.
Manual resolution of a deviation that is not waiting returns `409`.

On `RESOLVED`, ODM publishes `DeviationResolved` to Solace topic `order/deviation/resolved`; the
odm-service log shows `Received DeviationResolved event: deviationKey=...` from the demo consumer.
In Temporal UI: `autorecovery-SP-1001` Completed with `"AUTO_RECOVERED"`; `autorecovery-PSF-2001`
shows `WorkflowExecutionSignaled` (`manualResolution`) then `CloseRecovery`.

## Flow 3 - Full-Serve Deviation Lifecycle

OE/OEP publish a deviation signal to Solace. ODM consumes `ODM.DEVIATION.SIGNAL.Q` (client
acknowledgement), validates the payload against the POC contract, ignores it if the order already
has an OPEN deviation, and otherwise starts `FullServeDeviationWorkflow`. The workflow creates the
OPEN deviation, notifies O360, evaluates the Drools `fullserveKSession`, publishes consumer alerts
to SPM/Dynamics on durable timers and waits for a resolution update from
`ODM.DEVIATION.UPDATE.Q` (delivered as a Temporal Signal). It then closes the deviation and
publishes the alert closure and the O360 closure. **OE/OEP, O360, SPM and Dynamics are
simulated**; the payload shapes are POC-defined, not an agreed contract. Needs only odm-service
and its infrastructure. Details: [docs/odm-fullserve-walkthrough.md](docs/odm-fullserve-walkthrough.md).

The local broker's REST port (9000) stands in for OE publishing to Solace:

```bash
curl -s -X POST http://localhost:9000/TOPIC/odm/in/deviation/signal/OE/ORD-7741265 \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-7741265","source":"OE","serviceType":"FULL_SERVE","market":"US",
       "deviationType":"IKEA_DELAYED_ORDER_STO_ITEM_HANDOUT","reasonCode":"NO_STORE_STOCK",
       "occurrence":"ORDER_PROCESSING"}'

curl -s 'http://localhost:8085/fullserve/deviations?orderId=ORD-7741265'      # OPEN + PUBLISHED notifications
curl -s 'http://localhost:8085/fullserve/demo/received?orderId=ORD-7741265'  # what the simulated SPM/Dynamics/O360 received

curl -s -X POST http://localhost:9000/TOPIC/odm/in/deviation/update/OE/ORD-7741265 \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"ORD-7741265","source":"OE","trigger":"COWORKER","resolutionStatus":"RESOLVED"}'

curl -s 'http://localhost:8085/fullserve/deviations?orderId=ORD-7741265'      # CLOSED + ALERT_CLOSURE + DEVIATION_CLOSED
```

Sending the signal again while the deviation is OPEN is ignored (log: `already has OPEN deviation
... - signal ignored`); a signal without `market` is rejected (message settled `REJECTED`). The same
ingress is available over REST without the broker: `POST /fullserve/deviations/signals`
(`202` accepted, `200` duplicate, `400` invalid, `409` retry later) and
`POST /fullserve/deviations/updates` (`202` signalled, `400` invalid, `404` no OPEN deviation,
`409` stale or workflow not running). In Temporal UI, `fullserve-deviation-ORD-7741265` shows the
timers, the `resolutionUpdate` Signal and the result `CLOSED`.

## Tests

```bash
./mvnw clean test                     # all five modules from the repository root
(cd odm-service && ./mvnw test)       # one module
./mvnw test -pl odm-service -Dtest='*FullServe*'  # one flow's tests
```

All tests run without Docker or any running infrastructure: workflows run on Temporal's in-memory
time-skipping test server, Drools rules are evaluated for real, and the database and Solace are
replaced by in-memory doubles or mocks. No test talks to a real Postgres, Temporal server or
Solace broker; those integrations are verified by running the flows above.

| Flow | Main test classes |
|---|---|
| Order Lifecycle | `OrderLifecycleWorkflowImplTest`, `OrderLifecycleActivitiesImplTest`, `OrderStatusTransitionsTest`, `OrderControllerTest` (order-service); `DroolsDecisionEngineTest`, `DeviationApplicationServiceTest`, `DeviationControllerTest` (odm-service); inventory/payment/notification unit tests |
| Auto-Recovery | `AutorecoveryEndToEndTest`, `AutorecoveryWorkflowImplTest`, `DeviationRecoveryServiceTest`, `AutorecoveryDecisionEngineTest`, `RecoveryDeviationInvariantTest` |
| Full Serve | `FullServeDeviationEndToEndTest`, `FullServeRulesEngineTest`, `SolaceFullServeListenerTest`, `FullServeDeviationControllerTest` |

If the build fails with `Failed connecting to the daemon in 4 retries` (Kotlin compile daemon
blocked by the environment), add `-Dkotlin.compiler.daemon=false`.

## Troubleshooting

- **Temporal admin-tools / namespace containers restart a few times on first start**: they wait
  for `temporal-postgresql` and retry; allow a couple of minutes.
- **A service exits at startup**: check `docker compose logs <service>`. A Solace or Postgres
  connection error at startup means the dependency was not reachable; the containers restart up
  to 5 times. When running on the host, check that `.env` was exported (`DB_USERNAME`/`DB_PASSWORD`).
- **`localhost` vs service names**: on the host use `localhost` and the host ports; inside
  containers use the compose service names (`odm-postgres:5432`, `temporal:7233`,
  `tcp://solace:55555`). Compose already sets these for the containerised services.
- **Port already in use**: a service is probably already running on the host (Option B) - stop it
  before starting the container, or the reverse.
- **Full-Serve message keeps being redelivered**: the message was settled `FAILED` (e.g. Temporal
  unreachable). The local queue has unlimited, immediate redelivery; see the walkthrough's Known gaps.
- **Workflow stuck `Running` with no progress**: no worker is polling its task queue - check that the
  owning service (order-service or odm-service) is running and connected to Temporal.

## Limitations, simulated integrations and external dependencies

- **Simulated**: OE/OEP publishers (curl to the broker REST port, or the REST ingress), O360, SPM and
  Dynamics (`FullServeDemoConsumer`), Fulfillment Options and Rebook (mock clients), the
  DeviationResolved downstream consumer, payment outcomes (fixed by customer ID).
- **POC-defined, not agreed**: Full-Serve payloads, topic and queue names, alert schedule and markets,
  Auto-Recovery market policy, all Drools rule content.
- **Solace**: the bundled broker is the free PubSub+ Standard image for local use. A shared or managed
  broker cannot be bundled; point the services at it with the `SOLACE_*` variables (see
  [docs/solace.md](docs/solace.md)). Nothing was tested against a non-local broker.
- **Not production-ready**: no authentication, no actuator health/metrics, default Solace VPN and
  client user, local throwaway credentials, the in-memory notification store, and the
  known gaps listed in [docs/odm-fullserve-walkthrough.md](docs/odm-fullserve-walkthrough.md#known-gaps)
  and [docs/autorecovery.md](docs/autorecovery.md).

## Verification status

<!-- Keep this table factual: only record what was actually executed, with the date. -->

Recorded on 2026-10-20 on a local Linux machine (Docker 29, Compose v5, JDK 21), against the
working tree of `feature/full-serve-deviation-lifecycle-poc` (uncommitted changes included).
The Docker runs used a separate Compose project with a scratch override that only removes the
fixed `container_name`s and shifts the host ports, so that it could run beside an existing local
stack; environment, health checks, dependencies and networks were identical to this
`docker-compose.yml` (checked with `docker compose config`). The default ports and container
names themselves were not exercised.

| What | Result |
|---|---|
| `./mvnw -B clean test -Dkotlin.compiler.daemon=false` | Exit 0. 140 tests, 0 failures, 0 errors, 0 skipped (order 18, inventory 9, payment 6, notification 3, odm 104) |
| `docker compose --profile apps build` | All five images built with the classic builder (no BuildKit); runtime user `app` (non-root) |
| `docker compose --profile apps up -d --wait` from empty volumes | All services healthy in about 1 min; Flyway applied (odm V1-V5, others V1); Temporal shows pollers on all three task queues; odm-service provisioned both Full-Serve queues with their topic subscriptions |
| Order Lifecycle Scenarios 1-4 (scripted, in Docker) | Passed: COMPLETED / ORDER_FAILED (FAIL_ORDER) / COMPLETED after RETRY / ORDER_CANCELLED after RETRY, RETRY, COMPENSATE; notification-service received `order/confirmed` for the completed orders |
| Auto-Recovery Scenarios 5-6 (scripted, in Docker) | Passed: AUTO_RECOVERED; MANUAL wait, Signal, MANUALLY_RESOLVED; duplicate `200`; second resolution `409`; DeviationResolved event received by the demo consumer |
| Full Serve through the Solace queues (scripted, in Docker) | Passed with the default schedule: OPEN, O360 notification and alert #1 to SPM/Dynamics, duplicate ignored, invalid `400`, update -> CLOSED with alert closure and O360 closure, late update ignored. Alert #2 on its timer was checked in an earlier run with `ODM_FULLSERVE_ALERT_SCHEDULE=0s,20s,10m` |
| Restart: `down` (volumes kept), then `up` | Passed (earlier run): waiting Auto-Recovery and Full-Serve workflows resumed and closed; earlier orders intact |
| Graceful stop (`docker compose stop`) | odm-service and order-service stopped in about 1 s on SIGTERM, shutdown hooks ran |
| Option B (services on the host with `./mvnw spring-boot:run`) | Not re-run as part of this verification |
| macOS, Windows, ARM machines | Not verified |
| Against a shared/company Solace broker or Temporal cluster | Not verified |
| Failure injection (broker/Temporal/DB outage, DMQ, several ODM instances) | Not verified live; covered only by automated tests with doubles where the docs say so |
