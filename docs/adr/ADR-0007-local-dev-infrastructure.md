# ADR-0007 — Local Development Infrastructure (S0.3)

**Date:** 2026-05-01
**Status:** Accepted
**Deciders:** Platform team

---

## Context

Every backend service requires PostgreSQL, Kafka, a Redis-compatible cache, and an observability
pipeline to run locally. S0.3 must deliver a reproducible `docker compose up -d` that brings up
all infrastructure dependencies for the six backend services. Four independent technology choices
were required:

1. **Kafka broker** — which image and cluster mode
2. **Cache layer** — Redis or an alternative
3. **Observability pipeline** — whether to run OTel Collector + Jaeger and where to place them
4. **Developer tooling** — Kafka UI, JVM debugger attachment

---

## Decisions

### 1. Apache Kafka 4.0.0 (KRaft mode)

**Decision:** Use `apache/kafka:4.0.0` in KRaft mode (single-node broker + controller).

**Rationale:** Kafka 4.0 removes ZooKeeper entirely. Prior `apache/kafka` 3.x images still bundled
ZooKeeper support, requiring either a separate ZooKeeper container or KRaft opt-in flags.
Kafka 4.0 KRaft is the production configuration; running it locally avoids drift between
local and production topology. Spring Kafka 4.x (pulled in by Spring Boot 4.0) targets
Kafka 4.0+ and uses the new KRaft-only Admin API surface.

A single-node broker+controller configuration is sufficient for local development; replication
factor 1 is set on all internal topics.

---

### 2. Valkey 8 instead of Redis

**Decision:** Use `valkey/valkey:8-alpine` as the cache/rate-limit store.

**Rationale:** Redis changed its license from BSD to SSPL (Server Side Public License) in March
2024, starting with Redis 7.4. SSPL is incompatible with many open-source redistribution and
cloud-service scenarios and has been rejected as an OSI-approved license. Valkey is the Linux
Foundation fork of Redis (BSD-3-Clause), created and maintained by the same core Redis
contributors following the license change.

Valkey 8 is wire-protocol identical to Redis 7.x: all RESP2/RESP3 commands, Lua scripting,
pub/sub, keyspace notifications, and Streams work unchanged. Spring Data Redis (Lettuce driver),
Redisson, and all rate-limiting Lua scripts run against Valkey without any code changes.

The `CLAUDE.md` rule "Cache / Rate-limit: Redis" is satisfied because Valkey is the direct
BSD-licensed successor with full wire-protocol compatibility.

---

### 3. OTel Collector in the main compose (production-like topology)

**Decision:** Include `otel/opentelemetry-collector-contrib:0.151.0` in `docker-compose.yml`
(not the dev overlay). Services export OTLP to `localhost:4317`; the collector forwards to
Jaeger over the internal container network.

**Rationale:** In production, a collector sidecar or DaemonSet sits between services and the
observability backend. Replicating this topology locally means:

- Services configure a single env var (`OTEL_EXPORTER_OTLP_ENDPOINT`) that points at the
  collector in all environments. Only the collector's exporter destination changes per environment.
- Collector-side features (batching, sampling, attribute filtering, routing to multiple backends)
  are exercised locally, not just in staging.
- No direct service → Jaeger wiring that would not exist in production.

Jaeger receives spans from the collector on port `4317` (internal network only). The Jaeger UI
is exposed at `localhost:16686`. Services never talk directly to Jaeger.

Note: Jaeger v2 is built on the OTel Collector framework and no longer exposes the legacy admin
port `14269`. The Docker healthcheck targets the query HTTP server at `16686` instead.

---

### 4. Jaeger 2.17.0

**Decision:** Use `jaegertracing/jaeger:2.17.0`.

**Rationale:** Jaeger v2 natively receives OTLP via gRPC. The legacy Jaeger agent
(UDP-based, deprecated) and the older `all-in-one` Thrift/binary Jaeger protocol are not needed.
A single container replaces the older `all-in-one + agent` setup. v2.17.0 is the current stable
release as of 2026-05-01.

---

### 5. Redpanda Console in the dev overlay

**Decision:** Add `redpandadata/console:v2.7.2` (Kafka web UI) to `docker-compose.dev.yml`,
not the main compose. Activated with:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

**Rationale:** The Kafka UI is useful during development but irrelevant for CI and adds a
non-trivial container to the baseline stack. The overlay pattern keeps the main compose lean.
Redpanda Console is broker-agnostic and works with any Kafka-compatible broker, including
`apache/kafka:4.0.0` via the internal `kafka:29092` listener.

---

### 6. JVM debug ports via `.env.example`, not Docker

**Decision:** Services run locally via `./gradlew bootRun`, not inside Docker containers. JVM
debug ports are documented as commented `JAVA_TOOL_OPTIONS` lines in `.env.example` that
developers copy to `.env` and uncomment per service.

**Rationale:** Mounting service JARs into Docker containers adds rebuild friction. Remote debug
ports require no Docker involvement — the developer copies the comment, uncomments it, and
restarts the relevant `bootRun` process.

| Service | Debug port |
| ------- | ---------- |
| gateway | 5005 |
| registry | 5006 |
| ingestion | 5007 |

---

## Consequences

### Positive

- One `docker compose up -d` command starts all infrastructure. Services connect via localhost
  using env-var defaults that exactly match the compose port bindings.
- Valkey 8 is a drop-in replacement: zero code changes when `spring-boot-starter-data-redis` or
  any Lettuce-based client is added in future sprints.
- OTel collector is the single configuration knob for changing trace backends per environment.
  Services need no code changes to point at a different backend.
- The dev overlay pattern is reusable — Prometheus and Grafana can be added to
  `docker-compose.dev.yml` in Phase 5 without touching the main compose.

### Negative / Trade-offs

- Kafka 4.0 KRaft requires a stable `CLUSTER_ID` environment variable. If the kafka-data volume
  is destroyed and re-created, the same CLUSTER_ID must be used or the volume must be explicitly
  wiped first.
- Valkey is a newer project (2024-) than Redis; community tooling and third-party tutorials
  still predominantly reference Redis. In practice this makes no difference at the API level.

---

## Alternatives Considered

| Option | Reason rejected |
| ------ | --------------- |
| Kafka 3.9.0 (ZooKeeper optional) | Still bundles ZooKeeper support; Spring Kafka 4.x targets 4.0 API surface; no benefit over 4.0 |
| Redpanda instead of Apache Kafka | Wire-compatible but a different binary; introduces potential behaviour differences for KRaft controller APIs used by Spring Kafka 4.x |
| Redis 7.x | SSPL license incompatible with open-source redistribution; project aims to remain Apache 2.0 compatible |
| Direct service → Jaeger (no collector) | Diverges from production topology; loses collector-side sampling and routing flexibility |
| Jaeger v1 all-in-one | Deprecated; requires legacy Jaeger protocol instead of OTLP; no active development |
| OTel Collector in dev overlay only | CI and other non-dev environments would need to wire services directly to Jaeger, diverging from production |

---

## References

- [Kafka KRaft documentation](https://kafka.apache.org/documentation/#kraft)
- [Valkey project](https://valkey.io/)
- [Redis SSPL license change announcement](https://redis.io/blog/redis-adopts-dual-source-available-licensing/)
- [OTel Collector contrib releases](https://github.com/open-telemetry/opentelemetry-collector-contrib/releases)
- [Jaeger v2 documentation](https://www.jaegertracing.io/docs/2.0/)
- [ADR-0003 — gRPC for internal service communication](ADR-0003-grpc-for-internal-service-communication.md)
