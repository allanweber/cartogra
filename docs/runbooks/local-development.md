# Runbook: Local Development

## Prerequisites

| Tool | Minimum version | Install |
|------|----------------|---------|
| JDK | 25 | [Eclipse Temurin](https://adoptium.net/) |
| Gradle | 8.14 | Bundled via `./gradlew` wrapper |
| Docker Desktop | 4.x | [docker.com](https://www.docker.com/products/docker-desktop/) |
| Node.js | 22 LTS | [nodejs.org](https://nodejs.org/) |
| pnpm | 9.x | `npm install -g pnpm` |

---

## 1. Clone and bootstrap

```bash
git clone https://github.com/your-org/cartogra.git
cd cartogra
cp .env.example .env          # fill in secrets (see §2)
```

---

## 2. Environment variables (`.env`)

Copy `.env.example` to `.env`. The following variables are required to start all services locally:

| Variable | Description |
|----------|-------------|
| `POSTGRES_PASSWORD` | Local Postgres superuser password |
| `REDIS_PASSWORD` | Local Redis auth password |
| `JWT_SECRET` | 256-bit secret for gateway JWT signing |
| `RESEND_API_KEY` | Resend API key for OTP emails (`re_...`) |
| `GITHUB_PAT` | GitHub Personal Access Token with `repo` scope (for SCM ingestion) |
| `AZDO_PAT` | Azure DevOps Personal Access Token (for SCM ingestion) |
| `AZDO_ORG` | Azure DevOps organization name |
| `CLAUDE_API_KEY` | Anthropic API key (skip if not testing intelligence service) |

**Never commit `.env`** — it is in `.gitignore`.

---

## 3. Start infrastructure

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d \
  postgres valkey kafka tempo loki prometheus otel-collector grafana
```

Wait for healthy status:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
```

Expected: all containers `(healthy)` or `Up`. Kafka may take 20–30 seconds on first start. Tempo and Loki must be healthy before `otel-collector` starts.

---

## 4. Run database migrations

Flyway runs automatically on service startup. To run migrations manually:

```bash
./gradlew :services:registry:flywayMigrate
./gradlew :services:topology:flywayMigrate
./gradlew :services:contract:flywayMigrate
./gradlew :services:intelligence:flywayMigrate
```

---

## 5. Start backend services

Open one terminal per service, or use the IntelliJ "Run Configurations":

```bash
# Terminal 1 — Gateway (port 8080)
./gradlew :services:gateway:bootRun

# Terminal 2 — Registry (port 8081)
./gradlew :services:registry:bootRun

# Terminal 3 — Topology (port 8082)
./gradlew :services:topology:bootRun

# Terminal 4 — Contract (port 8083)
./gradlew :services:contract:bootRun

# Terminal 5 — Intelligence (port 8084)
./gradlew :services:intelligence:bootRun

# Terminal 6 — Ingestion (port 8085)
./gradlew :services:ingestion:bootRun
```

Each service logs `Started <Name>Application` when ready.

---

## 6. Start the frontend

```bash
cd frontend
pnpm install
pnpm dev          # http://localhost:3000
```

The TanStack Start dev server proxies `/api/*` to `http://localhost:8080`.

---

## 7. Seed data

Load the Acme Fintech seed dataset for a realistic local environment:

```bash
./gradlew :seed:run
```

This creates:
- 1 tenant (`acme-fintech`)
- 6 teams
- 24 services with ownership, dependencies, and health data
- Sample API contracts and breaking-change history

---

## 8. Useful local URLs

| URL | Purpose |
|-----|---------|
| `http://localhost:3000` | Frontend |
| `http://localhost:3001` | Grafana (traces + logs + metrics) |
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8081/actuator/health` | Registry health |
| `http://localhost:5436` | PostgreSQL (`psql -U postgres -d cartogra`) |
| `http://localhost:6379` | Redis (`redis-cli`) |
| `http://localhost:9092` | Kafka bootstrap |
| `http://localhost:9090` | Prometheus (direct query) |
| `http://localhost:3200` | Tempo HTTP API |
| `http://localhost:8086` | Kafka UI |
| `http://localhost:8087` | Valkey UI |
| `http://localhost:8088` | pgAdmin |

---

## 9. Running tests

```bash
# All tests (unit + integration via Testcontainers)
./gradlew test

# Single service
./gradlew :services:registry:test

# Frontend
cd frontend && pnpm test
```

Testcontainers spins up ephemeral Postgres and Kafka containers. Docker Desktop must be running.

---

## 10. Common issues

### Port already in use

```bash
# Find and kill the process using the port (e.g., 8081)
lsof -ti:8081 | xargs kill -9   # macOS/Linux
netstat -ano | findstr :8081     # Windows (then taskkill /PID <PID> /F)
```

### Kafka consumer lag not clearing

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml restart kafka
# Wait 15 seconds, then restart the consumer service
./gradlew :services:topology:bootRun
```

### Migration checksum mismatch

Never edit a committed Flyway migration. If you need to fix a mistake, create a new migration (`V00N__fix_...`). If on a local-only branch, drop and recreate the database:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml down -v postgres
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d postgres
./gradlew :services:registry:flywayMigrate
```

### Flyway error: non-empty schema but no schema history table

Symptom:
- `Found non-empty schema(s) "public" but no schema history table`

Cause:
- Service points Flyway at shared `public` while using independent startup migrations.

Required setup per DB-backed service:
- JDBC URL uses `currentSchema=<service_schema>`
- Flyway sets `schemas=<service_schema>` and `default-schema=<service_schema>`
- Flyway history table is `flyway_schema_history` in that service schema

Example (`registry`):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5436/cartogra?currentSchema=registry
  flyway:
    create-schemas: true
    schemas: registry
    default-schema: registry
    table: flyway_schema_history
```

### OTel traces not appearing in Grafana/Tempo

Check `OTEL_EXPORTER_OTLP_ENDPOINT` in your shell (default: `http://localhost:4317`). Verify the observability stack is running and healthy:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
# tempo, loki, prometheus, otel-collector, grafana should all be (healthy)
```

Query Tempo directly to confirm traces are arriving:

```bash
curl "http://localhost:3200/api/search?limit=5"
```

Query Loki directly to confirm logs are arriving:

```bash
curl -G "http://localhost:3100/loki/api/v1/query" \
  --data-urlencode 'query={service_name="gateway"}' \
  --data-urlencode 'limit=5'
```

Query Prometheus to confirm metrics are arriving:

```bash
curl "http://localhost:9090/api/v1/query?query=cartogra_jvm_memory_used_bytes"
```

Open `http://localhost:3001` → Explore → Tempo Search to browse traces. Click any span to navigate to correlated Loki logs and RED metrics panels.

## Service Auto-Discovery

### Namespace label contract (Kubernetes)

Cartogra discovers K8s Services in namespaces bearing the label:

```yaml
cartogra.io/tenant-id: <UUID>
```

Set this label on any namespace you want Cartogra to monitor:

```bash
kubectl label namespace my-app cartogra.io/tenant-id=<your-tenant-uuid>
```

The KubernetesWorker must be enabled (`ingestion.workers.k8s.enabled=true`) and a synthetic
connection UUID must be configured (`ingestion.workers.k8s.connection-id=<UUID>`).

### Health status mapping

| Endpoints state                                      | Cartogra healthStatus |
| ---------------------------------------------------- | --------------------- |
| Resource absent or no subsets                        | UNKNOWN               |
| All subsets have ready addresses only                | HEALTHY               |
| Some subsets have both ready and not-ready addresses | DEGRADED              |
| All subsets have only not-ready addresses            | UNHEALTHY             |

### Tech-stack detection rules (SCM)

Cartogra probes each non-archived repository via the SCM API for these files:

| File                                          | Technology detected |
| --------------------------------------------- | ------------------- |
| `pom.xml`                                     | java                |
| `pom.xml` containing `spring-boot`            | spring-boot         |
| `build.gradle` or `build.gradle.kts`          | java                |
| `build.gradle*` containing `spring-boot`      | spring-boot         |
| `package.json`                                | javascript          |
| `go.mod`                                      | go                  |
| `Cargo.toml`                                  | rust                |
| `requirements.txt`                            | python              |
| `Dockerfile` FROM `eclipse-temurin:*`         | java                |
| `Dockerfile` FROM `node:*`                    | javascript          |
| `Dockerfile` FROM `python:*`                  | python              |
| `Dockerfile` FROM `golang:*`                  | go                  |
| `Dockerfile` FROM `rust:*`                    | rust                |

Detection is additive: multiple signals result in a list (e.g., `["java","spring-boot"]`).

## To remove the database container + volume and recreate it

```bash
# Run from repo root
docker compose -f infra/docker-compose/docker-compose.dev.yml stop postgres
docker compose -f infra/docker-compose/docker-compose.dev.yml rm -f postgres
docker volume rm cartogra-dev_postgres-data
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d postgres
```
