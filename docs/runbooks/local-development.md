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
| `GITHUB_APP_ID` | GitHub App ID (skip if not testing SCM ingestion) |
| `GITHUB_APP_PRIVATE_KEY` | GitHub App private key PEM (base64) |
| `CLAUDE_API_KEY` | Anthropic API key (skip if not testing intelligence service) |

**Never commit `.env`** — it is in `.gitignore`.

---

## 3. Start infrastructure

```bash
docker compose up -d postgres redis kafka zookeeper
```

Wait for healthy status:

```bash
docker compose ps
```

Expected: all containers `(healthy)` or `Up`. Kafka may take 20–30 seconds on first start.

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

Each service logs `Started <Name>Application` when ready. OTel traces will print to stdout in dev profile.

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
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8081/actuator/health` | Registry health |
| `http://localhost:5432` | PostgreSQL (`psql -U postgres -d cartogra`) |
| `http://localhost:6379` | Redis (`redis-cli`) |
| `http://localhost:9092` | Kafka bootstrap |
| `http://localhost:16686` | Jaeger UI (traces) |
| `http://localhost:3001` | Grafana (metrics) |

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
docker compose restart kafka
# Wait 15 seconds, then restart the consumer service
./gradlew :services:topology:bootRun
```

### Migration checksum mismatch

Never edit a committed Flyway migration. If you need to fix a mistake, create a new migration (`V00N__fix_...`). If on a local-only branch, drop and recreate the database:

```bash
docker compose down -v postgres
docker compose up -d postgres
./gradlew :services:registry:flywayMigrate
```

### OTel traces not appearing in Jaeger

Check the `OTEL_EXPORTER_OTLP_ENDPOINT` env var in `.env`. Default: `http://localhost:4317`. Ensure the `otel-collector` container is running:

```bash
docker compose up -d otel-collector jaeger
```
