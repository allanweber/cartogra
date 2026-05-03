# Plan: S0.3 — Local Stack (docker-compose)

## Context

S0.3 delivers the local infrastructure every service depends on: PostgreSQL, Kafka, Valkey, and the
observability stack (OTel Collector + Jaeger). `infra/docker-compose/` exists but is empty; no
`.env.example` exists yet. Services already use env-var-driven config with matching defaults
(`cartogra` DB, `localhost` ports).

User decisions:

- **Kafka**: Apache Kafka 4.0.0 KRaft (ZooKeeper removed in 4.x; Spring Kafka 4.x targets this)
- **Cache**: Valkey 8 (`valkey/valkey:8-alpine`) — Linux Foundation Redis fork, BSD-3 licensed,
  fully API-compatible drop-in; all Spring Data Redis / Lua rate-limit scripts work unchanged
- **OTel Collector**: `otel/opentelemetry-collector-contrib:v0.151.0`
- **Jaeger**: `jaegertracing/jaeger:2.17.0`
- **Observability placement**: main compose (production-like pipeline topology)
- **Dev extras**: Redpanda Console (Kafka web UI) + JVM debug port documentation

---

## Files to create / update

### 1. `infra/docker-compose/docker-compose.yml`

| Container | Image | Host ports |
|---|---|---|
| `postgres` | `postgres:16-alpine` | `5432:5432` |
| `valkey` | `valkey/valkey:8-alpine` | `6379:6379` |
| `kafka` | `apache/kafka:4.0.0` | `9092:9092` (external), `29092` internal only |
| `otel-collector` | `otel/opentelemetry-collector-contrib:v0.151.0` | `4317:4317` gRPC, `4318:4318` HTTP |
| `jaeger` | `jaegertracing/jaeger:2.17.0` | `16686:16686` UI |

**Kafka KRaft** (single-node broker+controller):

- `KAFKA_PROCESS_ROLES: broker,controller`
- Two listeners: `PLAINTEXT` on `9092` (host-facing), `DOCKER_INTERNAL` on `29092` (container-to-container)
- `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093`
- `CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk` (stable fixed value — avoids volume re-init on restart)
- `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"` for local dev convenience
- Replication factor = 1 for all internal topics

**OTel Collector** receives OTLP on `4317`/`4318`; exports to Jaeger at `jaeger:4317` over the
internal container network (no host port conflict). Config loaded via `./otel-collector.yml` volume
mount.

**Jaeger v2** receives forwarded spans from the collector on internal port `4317`; only `16686` is
exposed to the host for the UI.

**PostgreSQL**: single `cartogra` database; user `cartogra`; password `${POSTGRES_PASSWORD:-cartogra}`.

Named volumes: `postgres-data`, `valkey-data`, `kafka-data`.

Network: `cartogra-net` (bridge).

Healthchecks on all five containers; `depends_on: condition: service_healthy` wiring where needed.

---

### 2. `infra/docker-compose/otel-collector.yml`

Minimal pipeline — traces and metrics only for Phase 0:

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls: { insecure: true }

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/jaeger]
    metrics:
      receivers: [otlp]
      exporters: [otlp/jaeger]
```

---

### 3. `infra/docker-compose/docker-compose.dev.yml`

Overlay file (`docker compose -f docker-compose.yml -f docker-compose.dev.yml up`).

Adds:

| Container | Image | Host port | Purpose |
|---|---|---|---|
| `kafka-ui` | `redpandadata/console:v2.7.2` | `8086:8080` | Kafka topic / message browser |

`kafka-ui` volume-mounts `./console-config.yml` pointing at `kafka:29092` (internal listener).

**JVM debug ports**: services run locally via `./gradlew bootRun`, not in Docker. Documented in
`.env.example` as commented `JAVA_TOOL_OPTIONS` lines the developer uncomments per service
(gateway → 5005, registry → 5006, ingestion → 5007).

---

### 4. `infra/docker-compose/console-config.yml`

```yaml
kafka:
  brokers:
    - kafka:29092
```

---

### 5. `.env.example` (repo root)

```dotenv
# ── PostgreSQL ───────────────────────────────────────────────
DB_HOST=localhost
DB_PORT=5432
DB_NAME=cartogra
DB_USER=cartogra
DB_PASSWORD=cartogra

# ── Valkey (Redis-compatible) ────────────────────────────────
REDIS_HOST=localhost
REDIS_PORT=6379

# ── Kafka ────────────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# ── OpenTelemetry ────────────────────────────────────────────
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=none

# ── Service routing (gateway → downstream) ───────────────────
REGISTRY_HOST=localhost
REGISTRY_PORT=8081

# ── JVM remote debug (uncomment per service when needed) ─────
# gateway  → JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
# registry → JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006
# ingestion→ JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5007
```

---

### 6. `README.md` — update "Getting Started / Local development" section

Add a section covering:

- `cd infra/docker-compose && docker compose up -d` to start the main stack
- `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d` for dev extras
- `cp .env.example .env` for env var setup
- Port reference table (see Verification section below)

---

### 7. `docs/adr/ADR-0007-local-dev-infrastructure.md` — new ADR

Documents every technology decision made for S0.3. Sections:

**Status**: Accepted

**Context**: Need a reproducible local stack for all six services. Decisions involve four
independent choices: Kafka broker, cache layer, observability pipeline, and dev tooling.

**Decisions and rationale**:

1. **Kafka 4.0.0 KRaft** — ZooKeeper removed in 4.x; single-process broker+controller; aligns
   with Spring Kafka 4.x (Spring Boot 4.0 dependency). No extra container for coordination.

2. **Valkey 8 over Redis** — Redis changed to SSPL license in March 2024, making it incompatible
   with many open-source redistribution scenarios. Valkey is the Linux Foundation fork (BSD-3),
   fully API-compatible; all Spring Data Redis clients, Lettuce, and Lua scripting work unchanged.
   CLAUDE.md rule "use Redis" is satisfied because Valkey is wire-protocol identical.

3. **OTel Collector in main compose** — mirrors production topology where a collector sidecar or
   DaemonSet sits between services and the backend. Services export to `localhost:4317` in all
   environments; only the collector destination changes per environment.

4. **Jaeger 2.17.0** — natively receives OTLP; no legacy Jaeger agent required; single container
   replaces the older all-in-one + agent setup.

5. **Redpanda Console (dev overlay)** — Kafka web UI works with any Kafka-compatible broker; kept
   in the dev overlay to keep the main compose lean for CI.

**Consequences**:

- Any future service that uses `spring-boot-starter-data-redis` / `spring-data-valkey` will
  connect without config changes (same wire protocol).
- OTel environment variables are the single knob to change exporter targets across environments.
- The dev overlay pattern extends naturally to Phase 5 when additional tooling is added
  (Prometheus, Grafana).

---

## File list (summary)

| File | Action |
|---|---|
| `infra/docker-compose/docker-compose.yml` | Create |
| `infra/docker-compose/otel-collector.yml` | Create |
| `infra/docker-compose/docker-compose.dev.yml` | Create |
| `infra/docker-compose/console-config.yml` | Create |
| `.env.example` | Create |
| `README.md` | Update — add local-dev section |
| `docs/adr/ADR-0007-local-dev-infrastructure.md` | Create |

---

## Verification

1. `cd infra/docker-compose && docker compose up -d` — all five containers reach `healthy`
2. `psql -h localhost -U cartogra -d cartogra -c '\l'` — DB exists; confirms Flyway can run
3. `redis-cli -h localhost ping` — returns `PONG` (Valkey responds identically)
4. `curl -s http://localhost:9092` (or Kafka UI) — Kafka broker responds
5. `curl -s http://localhost:4317` — OTel Collector gRPC port is reachable
6. `curl -s http://localhost:16686` — Jaeger UI loads
7. Dev overlay: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d`
   — Kafka UI reachable at `http://localhost:8086`
8. `./gradlew :services:registry:bootRun` — Flyway applies V001–V004 against the Compose Postgres
   without errors
