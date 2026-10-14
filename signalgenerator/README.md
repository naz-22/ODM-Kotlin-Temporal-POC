# Signal Generator

A Kotlin / Spring Boot service that exposes a REST endpoint and publishes order deviation signals to a Solace PubSub+ broker topic.

## Architecture

Hexagonal layout under `com.example.signalgenerator`:

- `adapters/input/web` – REST controller (`SignalController`) and request DTO
- `application` – `SignalService` and output port (`SignalPublisherPort`)
- `adapters/output/solace` – `SolaceSignalPublisher` (publishes to Solace)
- `domain` – `OrderDeviationSignal`
- `configuration` – Solace session configuration

## Prerequisites

- JDK 25 (the Docker image uses `eclipse-temurin:25`)
- Docker (for Solace and/or containerised run)
- Maven is provided through the wrapper (`./mvnw`)

## 1. Start Solace

```bash
docker run -d  
  --name solace   
  --shm-size=1g   
  --ulimit nofile=1048576:1048576   
  -p 8080:8080   
  -p 55555:55555   
  -p 1883:1883   
  -p 5672:5672   
  -p 8008:8008   
  -p 9000:9000   
  -e username_admin_globalaccesslevel=admin   
  -e username_admin_password=admin   
  solace/solace-pubsub-standard:latest
```

Admin UI: <http://localhost:8080> (admin / admin). SMF port: `55555`.

## 2. Run the application

### Normally (Maven)

```bash
cd signalgenerator
./mvnw spring-boot:run
```

Or build and run the jar:

```bash
./mvnw clean package
java -jar target/signalgenerator-*.jar
```

### With Docker

```bash
cd signalgenerator
docker build -t signalgenerator .
docker run -d --name signalgenerator \
  --network host \
  signalgenerator
```

`--network host` lets the container reach Solace on `localhost:55555` (Linux). Alternatively, avoid host networking and point at the broker explicitly:

```bash
docker run -d --name signalgenerator \
  -p 8086:8086 \
  -e SOLACE_HOST=tcp://host.docker.internal:55555 \
  --add-host=host.docker.internal:host-gateway \
  signalgenerator
```

## Configuration

Set via environment variables (defaults in `src/main/resources/application.yml`):

| Variable | Default | Description |
|---|---|---|
| `SOLACE_HOST` | `tcp://localhost:55555` | Solace broker URL |
| `SOLACE_VPN` | `default` | Message VPN |
| `SOLACE_CLIENT_USERNAME` | `admin` | Client username |
| `SOLACE_CLIENT_PASSWORD` | `admin` | Client password |
| `SERVER_PORT` | `8086` | HTTP port |
| `ORDER_DEVIATION_SIGNAL_TOPIC` | `order/deviation/signal` | Topic published to |

## API

`POST /signals/order-deviation` → `202 Accepted`

```bash
curl -X POST http://localhost:8086/signals/order-deviation \
  -H 'Content-Type: application/json' \
  -d '{
    "orderId": "order-123",
    "source": "WMS",
    "serviceType": "HOME_DELIVERY",
    "market": "SE",
    "deviationType": "SHORT_PICK",
    "reasonCode": "OOS",
    "occurrence": "2026-10-14T10:00:00Z",
    "reasonCaused": "Out of stock"
  }'
```

All fields are required, non-blank strings.

## Tests

```bash
./mvnw test
```
