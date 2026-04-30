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
|---------|------|------|
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
# 1. Start the local stack (Postgres, Kafka/Redpanda, Redis)
docker compose -f infra/docker-compose/docker-compose.yml up -d

# 2. Run all services
./gradlew bootRun

# 3. Start the frontend
cd frontend && pnpm install && pnpm dev
```

Open [http://localhost:3000](http://localhost:3000).

See [docs/runbooks/local-development.md](docs/runbooks/local-development.md) for full environment setup, env vars, and troubleshooting.

---

## Documentation

| Doc | Purpose |
|-----|---------|
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
