# Cartogra Backend Setup

## Stack

- Java 25
- Spring Boot 4 (microservices)
- Spring Data JDBC + PostgreSQL
- Flyway migrations per service
- Apache Kafka
- Redis
- OpenTelemetry (OTLP)

## Services

- gateway: http://localhost:8080
- registry: http://localhost:8081
- topology: http://localhost:8082
- contract: http://localhost:8083
- intelligence: http://localhost:8084

## Prerequisites

- Java 25 installed and available on PATH
- Gradle wrapper available in the repository
- Docker and Docker Compose
- Optional: Make sure ports 8080-8084, 5432, 6379, and Kafka broker ports are free

## Local Infrastructure

Start local dependencies from repository root:

```bash
docker compose -f infra/docker-compose/docker-compose.yml up -d
```

Optional development overrides:

```bash
docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.dev.yml up -d
```

Stop infrastructure:

```bash
docker compose -f infra/docker-compose/docker-compose.yml down
```

## Environment

Set environment variables per service as needed. Typical values:

```env
SPRING_PROFILES_ACTIVE=local
DB_HOST=localhost
DB_PORT=5432
DB_NAME=cartogra
DB_USER=cartogra
DB_PASSWORD=cartogra
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
REDIS_HOST=localhost
REDIS_PORT=6379
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
```

Use service-specific application-local settings where applicable.

## Build All Services

From repository root:

```bash
./gradlew build
```

On Windows PowerShell:

```powershell
.\\gradlew.bat build
```

## Run a Single Service

From repository root, examples:

```bash
./gradlew :services:gateway:bootRun
./gradlew :services:registry:bootRun
```

Windows PowerShell:

```powershell
.\\gradlew.bat :services:gateway:bootRun
.\\gradlew.bat :services:registry:bootRun
```

## Run Key Backend Services Together

Run each in a separate terminal:

```bash
./gradlew :services:gateway:bootRun
./gradlew :services:registry:bootRun
./gradlew :services:topology:bootRun
./gradlew :services:contract:bootRun
./gradlew :services:intelligence:bootRun
```

## Migrations

Flyway migrations run on service startup. To verify migrations via tests/build:

```bash
./gradlew test
```

## Health Checks

- Gateway: http://localhost:8080/actuator/health
- Registry: http://localhost:8081/actuator/health
- Topology: http://localhost:8082/actuator/health
- Contract: http://localhost:8083/actuator/health
- Intelligence: http://localhost:8084/actuator/health

## API Behavior Expectations

- Cartogra Spring REST endpoints return the response envelope:
  - success: data + traceId
  - error: error + traceId
- Response header X-Trace-Id should match traceId in the body
- traceparent must be propagated across backend service calls and Kafka messages

## Test

Run all backend tests:

```bash
./gradlew test
```

Run tests for one service:

```bash
./gradlew :services:registry:test
```

## Docker Build (Service Example)

```bash
docker build -f services/registry/Dockerfile -t cartogra-registry:v1.0.0 .
```

## Typical Local Workflow

1. Start local infrastructure with Docker Compose.
2. Build the monorepo with Gradle.
3. Start gateway and one domain service (for example, registry).
4. Verify actuator health endpoints.
5. Run tests before pushing changes.
