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
# 1. Copy env vars and start the local dev stack
cp .env.example .env
cd infra/docker-compose && docker compose -f docker-compose.dev.yml up -d

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
# Production-equivalent stack (Postgres, Kafka, Valkey, OTel Collector, Jaeger)
cd infra/docker-compose && docker compose up -d

# Dev stack — same services + Kafka UI, Valkey UI, and pgAdmin
cd infra/docker-compose && docker compose -f docker-compose.dev.yml up -d
```

**Containers and UIs (dev stack):**

| Container | Host port(s) | UI / access |
| --------- | ------------ | ----------- |
| PostgreSQL | 5436 | `psql -h localhost -p 5436 -U cartogra -d cartogra` |
| Valkey | 6380 | Key/value store (Redis-compatible) |
| Kafka | 9092 | Kafka broker |
| Jaeger | 16686 | [http://localhost:16686](http://localhost:16686) — distributed trace viewer |
| OTel Collector | 4317 (gRPC), 4318 (HTTP) | OTLP ingest endpoint |
| Kafka UI _(dev only)_ | 8086 | [http://localhost:8086](http://localhost:8086) — topic and message browser |
| Valkey UI _(dev only)_ | 8087 | [http://localhost:8087](http://localhost:8087) — Redis Commander key/value browser |
| pgAdmin _(dev only)_ | 8088 | [http://localhost:8088](http://localhost:8088) — PostgreSQL browser and query UI |

The dev-stack pgAdmin container is configured for local-only convenience with `SERVER_MODE=False` and `MASTER_PASSWORD_REQUIRED=False`, so it should not prompt for the normal pgAdmin web login or a separate master password.

If you override those settings, use pgAdmin with the default login `admin@cartogra.dev` and password `cartogra` unless you also override `PGADMIN_DEFAULT_EMAIL` or `PGADMIN_DEFAULT_PASSWORD` in `.env`.

The dev container also pins the desktop-mode user to `admin@cartogra.dev` so auto-login works with the persisted pgAdmin data volume instead of looking for pgAdmin's built-in default desktop account.

pgAdmin also preloads the local Postgres server as `Cartogra Local Postgres` using host `host.docker.internal` and port `5436`, which routes from the pgAdmin container to the host-mapped Postgres port.

Connect to the local database from pgAdmin with host `host.docker.internal`, port `5436`, database `cartogra`, username `cartogra`, and the same password you set in `POSTGRES_PASSWORD`.

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
