# Cartogra

[![CI](https://github.com/your-org/cartogra/actions/workflows/ci.yml/badge.svg)](https://github.com/your-org/cartogra/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Cartogra** is a living service registry and dependency intelligence platform for engineering teams managing distributed systems at scale.

It tracks your services, their owners, dependencies, API contracts, and health — and surfaces actionable intelligence about blast radius, breaking changes, and architectural anti-patterns.

---

## Why Cartogra

Service catalogs go stale because they're updated manually. Cartogra solves this by pulling live state from your SCM providers (GitHub, Azure DevOps) and Kubernetes clusters, detecting declared vs observed dependency drift, and running AI-assisted analysis over the resulting graph.

**Core pillars:**

- **Registry** — authoritative source of truth for services, teams, and ownership
- **Topology** — dependency graph with blast radius, cycle, and SPOF detection
- **Contracts** — breaking-change detection and consumer-producer compatibility matrix
- **Intelligence** — natural language queries and anti-pattern analysis powered by Claude

---

## Services

| Service | Port | Role |
| ------- | ---- | ---- |
| `gateway` | 8080 | Auth, routing, tenant injection, rate limiting |
| `registry` | 8081 | Service CRUD, team ownership, temporal history |
| `topology` | 8082 | Dependency graph, blast radius, cycle detection |
| `contract` | 8083 | Contract validation, breaking-change detection |
| `intelligence` | 8084 | NL queries, health score, anti-pattern analysis |
| `ingestion` | 8085 | SCM/K8s sync workers, webhook receivers |
| `frontend` | 3000 | TanStack Start web app |

---

## Quickstart

**Prerequisites:** Docker, JDK 25, Node 22+, pnpm

```bash
# 1. Start the local dev stack
cd infra/docker-compose && docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# 2. Run all services (from repo root)
./gradlew bootRun

# 3. Start the frontend
cd frontend && pnpm install && pnpm dev
```

Open [http://localhost:3000](http://localhost:3000).

See [docs/runbooks/local-development.md](docs/runbooks/local-development.md) for full environment setup, env vars, and troubleshooting.

---

## Local Infrastructure

The local stack lives in `infra/docker-compose/`. All services connect via `localhost` using the env-var defaults in `.env.example`.

```bash
# Base infra only (Postgres, Kafka, Valkey)
cd infra/docker-compose && docker compose -f docker-compose.yml up -d

# Full dev stack — base infra + observability + developer UIs
cd infra/docker-compose && docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

**Containers and ports (full dev stack):**

| Container | Host port(s) | UI / access |
| --------- | ------------ | ----------- |
| PostgreSQL | 5436 | `psql -h localhost -p 5436 -U cartogra -d cartogra` |
| Valkey | 6380 | Key/value store (Redis-compatible) |
| Kafka | 9092 | Kafka broker |
| OTel Collector | 4317 (gRPC), 4318 (HTTP) | OTLP ingest from all services |
| Grafana _(dev only)_ | 3001 | [http://localhost:3001](http://localhost:3001) — traces, logs, metrics correlation UI |
| Tempo _(dev only)_ | 3200 | Distributed trace backend (HTTP API) |
| Prometheus _(dev only)_ | 9090 | [http://localhost:9090](http://localhost:9090) — metrics query and scrape target |
| Kafka UI _(dev only)_ | 8086 | [http://localhost:8086](http://localhost:8086) — topic and message browser |
| Valkey UI _(dev only)_ | 8087 | [http://localhost:8087](http://localhost:8087) — Redis Commander key/value browser |
| pgAdmin _(dev only)_ | 8088 | [http://localhost:8088](http://localhost:8088) — PostgreSQL browser and query UI |

Grafana is pre-configured with Tempo, Loki, and Prometheus datasources and cross-signal correlation. Open **Explore → Tempo Search** to browse traces, then click any span to jump to correlated Loki logs and RED metrics.

The dev-stack pgAdmin container is configured for local-only convenience with `SERVER_MODE=False` and `MASTER_PASSWORD_REQUIRED=False`. Default login: `admin@cartogra.dev` / `cartogra` (or whatever you set in `PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD` in `.env`). The local Postgres server is pre-loaded as `Cartogra Local Postgres` — connect via host `host.docker.internal`, port `5436`, database `cartogra`, user `cartogra`.

---

## Configuration

**Local development requires no environment variables.** All service defaults (database, Kafka, Redis, OTel endpoints) are hardcoded in each service's `application.yml`. Docker Compose infra defaults are inlined in `docker-compose.dev.yml`.

For non-local environments (CI, staging, production) override via standard [Spring Boot env var binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables) — dots become underscores, e.g. `spring.datasource.url` → `SPRING_DATASOURCE_URL`.

### `gateway` (port 8080)

| Variable | Description | Example value |
| -------- | ----------- | ------------- |
| `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES_0_URI` | Registry service address | `http://registry:8081` |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | Trace exporter (HTTP) | `http://otel-collector:4318/v1/traces` |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Metrics exporter (HTTP) | `http://otel-collector:4318/v1/metrics` |
| `MANAGEMENT_OTLP_LOGGING_EXPORT_ENDPOINT` | Log exporter (HTTP) | `http://otel-collector:4318/v1/logs` |

### `registry` (port 8081) · `ingestion` (port 8085)

These services share the same database and OTel config shape. Future DB-backed services follow the same pattern.

| Variable | Description | Example value |
| -------- | ----------- | ------------- |
| `SPRING_DATASOURCE_URL` | JDBC connection string | `jdbc:postgresql://postgres:5432/cartogra` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `cartogra` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | _(inject from secret)_ |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | Trace exporter (HTTP) | `http://otel-collector:4318/v1/traces` |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Metrics exporter (HTTP) | `http://otel-collector:4318/v1/metrics` |
| `MANAGEMENT_OTLP_LOGGING_EXPORT_ENDPOINT` | Log exporter (HTTP) | `http://otel-collector:4318/v1/logs` |

### `topology` · `contract` · `intelligence` _(not yet scaffolded)_

Will follow the same pattern as `registry` once added.

---

## Documentation

| Doc | Purpose |
| --- | ------- |
| [docs/architecture/system-overview.md](docs/architecture/system-overview.md) | Architecture and service map |
| [docs/architecture/data-model.md](docs/architecture/data-model.md) | Database schema per service |
| [docs/architecture/kafka-topics.md](docs/architecture/kafka-topics.md) | Kafka topic inventory |
| [docs/api/](docs/api/) | OpenAPI specs per service |
| [docs/adr/](docs/adr/) | Architecture Decision Records |
| [docs/runbooks/](docs/runbooks/) | Operational runbooks |
| [AGENTS.md](AGENTS.md) | AI agent and slash command reference |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |

---

## Tech Stack

Java 25 · Spring Boot 4 · Spring Data JDBC · PostgreSQL · Apache Kafka · Redis · OpenTelemetry · TanStack Start · shadcn/ui · D3

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
